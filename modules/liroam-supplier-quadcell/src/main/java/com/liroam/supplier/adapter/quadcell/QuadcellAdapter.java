package com.liroam.supplier.adapter.quadcell;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.CredentialResult;
import com.liroam.supplier.spi.LookupQuery;
import com.liroam.supplier.spi.LookupQuery.WriteOperation;
import com.liroam.supplier.spi.LookupResult;
import com.liroam.supplier.spi.LookupResult.Effect;
import com.liroam.supplier.spi.Outcome;
import com.liroam.supplier.spi.ProvisionCommand;
import com.liroam.supplier.spi.ProvisionResult;
import com.liroam.supplier.spi.StatusCommand;
import com.liroam.supplier.spi.StatusResult;
import com.liroam.supplier.spi.SupplierAdapter;
import com.liroam.supplier.spi.SupplierCapabilities;
import com.liroam.supplier.spi.SupplierCapabilities.CredentialChannel;
import com.liroam.supplier.spi.SupplierCapabilities.InventoryMode;
import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;
import com.liroam.supplier.spi.SupplierCapabilities.TopUpMode;
import com.liroam.supplier.spi.SupplierCapabilities.UsageDimension;
import com.liroam.supplier.spi.TopUpCommand;
import com.liroam.supplier.spi.TopUpResult;
import com.liroam.supplier.spi.UsageSnapshot;
import com.liroam.supplier.spi.UsageSnapshot.CardStatus;
import com.liroam.supplier.spi.UsageSnapshot.PlanUsage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Quadcell Open API v1.3.3 适配器（Bean 名 {@value #ADAPTER_KEY}）。
 * <ul>
 *   <li>开卡 = 8.7 给 Idle 池卡开订阅（ICCID 在路径），返回的 {@code packId} 作卡与首购套餐的上游引用；加包 = 8.15。</li>
 *   <li>查档/用量 = 8.5：流量 MB 按 {@code bytes-per-mb} 换字节；时间串带偏移按偏移、不带按 {@code time-zone}；原值保留，原始报文脱敏卡号类字段。</li>
 *   <li>停用 / 恢复 / 终止 = 8.8 / 8.9 / 8.10；8.8 要求卡已激活，未激活停用由上游以 2004 拒绝。</li>
 *   <li>文档无交码接口、无回调、无幂等键：交码走导入/人工；写结果未知时按卡状态查档，加包无法查档直接转人工。</li>
 * </ul>
 * 不记日志：卡号与 API key 不出现在任何返回文本里，排障用上游响应头 {@code requestId}。
 */
public final class QuadcellAdapter implements SupplierAdapter {

    public static final String ADAPTER_KEY = "quadcell";

    static final SupplierCapabilities CAPABILITIES = new SupplierCapabilities(
            InventoryMode.POOL_REQUIRED,
            Set.of(CredentialChannel.IMPORT, CredentialChannel.MANUAL),
            Set.of(UsageDimension.DATA),
            Set.of(StatusOp.SUSPEND, StatusOp.RESUME, StatusOp.TERMINATE),
            TopUpMode.ADD_PACKAGE,
            false, false, false, 100);

    private static final int IDLE = 2;
    private static final int ACTIVATED = 4;
    private static final int SUSPENDED = 5;

    private enum FieldType { STRING, INT, BOOL }

    /** 套餐映射载荷（{@code lr_supplier_offering} 的 payload）可写的 8.7 Sim profile 字段。 */
    private static final Map<String, FieldType> PROFILE_FIELDS = Map.of(
            "svcCode", FieldType.STRING, "mode", FieldType.INT, "vDay", FieldType.INT, "actwPack", FieldType.BOOL);

    /** 8.7 {@code pack.*} 与 8.15 共用的 package 字段。 */
    private static final Map<String, FieldType> PACK_FIELDS = Map.of(
            "pcrfCode", FieldType.STRING, "pcrfReset", FieldType.INT, "autoAct", FieldType.BOOL, "priority", FieldType.STRING);

    private static final Set<String> MASKED_FIELDS = Set.of("iccid", "imsi", "msisdn", "imeis");
    private static final Pattern CARD_ID = Pattern.compile("[0-9A-Za-z]{6,32}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final QuadcellProperties properties;
    private final HttpClient http;

    public QuadcellAdapter(QuadcellProperties properties) {
        this.properties = properties;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getConnectTimeout());
        if (properties.getProxyHost() != null && !properties.getProxyHost().isBlank()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(properties.getProxyHost(), properties.getProxyPort())));
        }
        this.http = builder.build();
    }

    @Override
    public SupplierCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Outcome<ProvisionResult> provision(ProvisionCommand command) {
        String iccid = command.iccid();
        if (iccid == null || !CARD_ID.matcher(iccid).matches()) {
            return Outcome.rejected("BAD_REQUEST", "池模式开卡须带有效 ICCID");
        }
        if (command.offeringCode() == null || command.offeringCode().isBlank()) {
            return Outcome.rejected("BAD_PACKAGE", "套餐映射缺上游 packCode");
        }
        ObjectNode body = JSON.createObjectNode();
        ObjectNode pack = JSON.createObjectNode().put("packCode", command.offeringCode());
        for (Map.Entry<String, String> e : command.offeringPayload().entrySet()) {
            ObjectNode target = PROFILE_FIELDS.containsKey(e.getKey()) ? body : PACK_FIELDS.containsKey(e.getKey()) ? pack : null;
            Optional<String> error = target == null ? Optional.of("未知字段 " + e.getKey())
                    : put(target, e.getKey(), target == body ? PROFILE_FIELDS.get(e.getKey()) : PACK_FIELDS.get(e.getKey()), e.getValue());
            if (error.isPresent()) {
                return Outcome.rejected("BAD_PAYLOAD", "套餐映射载荷：" + error.get());
            }
        }
        if (!body.has("mode")) {
            if (properties.getDefaultMode() == null) {
                return Outcome.rejected("BAD_PAYLOAD", "开订阅须有 mode：在套餐映射载荷或 liroam.supplier.quadcell.default-mode 配置");
            }
            body.put("mode", properties.getDefaultMode());
        }
        body.set("pack", pack);
        return exchange(true, "POST", profilePath(iccid), body, Map.of("time-zone", properties.getTimeZone()),
                json -> packId(json).<Outcome<ProvisionResult>>map(id -> Outcome.ok(new ProvisionResult(iccid, id, Optional.empty())))
                        .orElseGet(() -> Outcome.unknown("上游 200 但未回 packId")));
    }

    @Override
    public Outcome<CredentialResult> fetchCredential(CardRef card) {
        return Outcome.rejected(Outcome.UNSUPPORTED, "上游无交码接口，交码走导入/人工");
    }

    @Override
    public Outcome<UsageSnapshot> queryUsage(CardRef card) {
        if (!validCard(card)) {
            return Outcome.rejected("BAD_REQUEST", "卡缺有效 ICCID");
        }
        return exchange(false, "GET", profilePath(card.iccid()), null, Map.of(), json -> snapshot(json, card.upstreamRef()));
    }

    @Override
    public Outcome<TopUpResult> addPlan(TopUpCommand command) {
        if (!validCard(command.card())) {
            return Outcome.rejected("BAD_REQUEST", "卡缺有效 ICCID");
        }
        if (command.offeringCode() == null || command.offeringCode().isBlank()) {
            return Outcome.rejected("BAD_PACKAGE", "套餐映射缺上游 packCode");
        }
        ObjectNode body = JSON.createObjectNode().put("packCode", command.offeringCode());
        for (Map.Entry<String, String> e : command.offeringPayload().entrySet()) {
            Optional<String> error = PACK_FIELDS.containsKey(e.getKey()) ? put(body, e.getKey(), PACK_FIELDS.get(e.getKey()), e.getValue())
                    : Optional.of("加包不接受字段 " + e.getKey());
            if (error.isPresent()) {
                return Outcome.rejected("BAD_PAYLOAD", "套餐映射载荷：" + error.get());
            }
        }
        return exchange(true, "POST", profilePath(command.card().iccid()) + "/package", body, Map.of(),
                json -> packId(json).<Outcome<TopUpResult>>map(id -> Outcome.ok(new TopUpResult(id)))
                        .orElseGet(() -> Outcome.unknown("上游 200 但未回 packId")));
    }

    @Override
    public Outcome<StatusResult> changeStatus(StatusCommand command) {
        if (!validCard(command.card())) {
            return Outcome.rejected("BAD_REQUEST", "卡缺有效 ICCID");
        }
        String path = profilePath(command.card().iccid());
        return switch (command.op()) {
            case SUSPEND -> exchange(true, "POST", path + "/suspend", null, Map.of(), json -> Outcome.ok(new StatusResult(CardStatus.SUSPENDED)));
            case RESUME -> exchange(true, "POST", path + "/resume", null, Map.of(), json -> Outcome.ok(new StatusResult(CardStatus.ACTIVE)));
            case TERMINATE -> exchange(true, "DELETE", path, null, Map.of(), json -> Outcome.ok(new StatusResult(CardStatus.TERMINATED)));
        };
    }

    @Override
    public Outcome<LookupResult> lookup(LookupQuery query) {
        WriteOperation op = query.operation();
        if (op == WriteOperation.ADD_PLAN) {
            return Outcome.rejected("LOOKUP_UNSUPPORTED", "上游不收请求号、同卡可重复加同一套餐，无法判定加包是否生效；请核对上游套餐后人工处理");
        }
        if (!validCard(query.card())) {
            return Outcome.rejected("BAD_REQUEST", "卡缺有效 ICCID");
        }
        Outcome<LookupResult> outcome = exchange(false, "GET", profilePath(query.card().iccid()), null, Map.of(), json -> {
            if (!json.path("status").isNumber()) {
                return Outcome.unknown("查档响应缺 status");
            }
            int status = json.path("status").asInt();
            boolean effective = switch (op) {
                case PROVISION -> status != IDLE;
                case SUSPEND -> status == SUSPENDED;
                case RESUME -> status == ACTIVATED;
                case TERMINATE -> status == IDLE;
                case ADD_PLAN -> throw new IllegalStateException();
            };
            String ref = op == WriteOperation.PROVISION ? firstPackId(json) : null;
            return Outcome.ok(effective ? new LookupResult(Effect.EFFECTIVE, ref) : new LookupResult(Effect.NOT_EFFECTIVE, null));
        });
        if (op == WriteOperation.TERMINATE && outcome instanceof Outcome.Rejected<LookupResult> r
                && r.code().equals(String.valueOf(QuadcellErrors.NO_RECORD))) {
            return Outcome.ok(new LookupResult(Effect.EFFECTIVE, null));
        }
        return outcome;
    }

    // ---- HTTP 与错误分级 ----

    private <T> Outcome<T> exchange(boolean write, String method, String path, JsonNode body, Map<String, String> headers,
            Function<JsonNode, Outcome<T>> onOk) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(properties.getReadTimeout())
                .header("content-type", "application/json")
                .header("X-api-key", properties.getApiKey())
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body.toString()));
        headers.forEach(request::header);
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            return Outcome.retryable("CONNECT_FAILED " + e.getClass().getSimpleName(), null);
        } catch (HttpTimeoutException e) {
            return write ? Outcome.unknown("READ_TIMEOUT") : Outcome.retryable("READ_TIMEOUT", null);
        } catch (IOException e) {
            if (causedByConnect(e)) {
                return Outcome.retryable("CONNECT_FAILED " + e.getClass().getSimpleName(), null);
            }
            return write ? Outcome.unknown("IO " + e.getClass().getSimpleName()) : Outcome.retryable("IO " + e.getClass().getSimpleName(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return write ? Outcome.unknown("INTERRUPTED") : Outcome.retryable("INTERRUPTED", null);
        }
        String requestId = response.headers().firstValue("requestId").orElse("-");
        if (response.statusCode() != 200) {
            return failure(write, response, requestId);
        }
        try {
            String text = response.body();
            return onOk.apply(text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text));
        } catch (JacksonException | DateTimeParseException | ArithmeticException e) {
            return Outcome.unknown("上游响应无法解析 " + e.getClass().getSimpleName() + " requestId=" + requestId);
        }
    }

    private <T> Outcome<T> failure(boolean write, HttpResponse<String> response, String requestId) {
        int status = response.statusCode();
        if (status == 429) {
            return Outcome.retryable("THROTTLED requestId=" + requestId, retryAfter(response));
        }
        List<JsonNode> errors = errors(response.body());
        if (!errors.isEmpty()) {
            JsonNode worst = errors.stream().filter(e -> QuadcellErrors.classify(e.path("code").asInt()) == QuadcellErrors.Kind.UNKNOWN).findFirst()
                    .or(() -> errors.stream().filter(e -> QuadcellErrors.classify(e.path("code").asInt()) == QuadcellErrors.Kind.REJECTED).findFirst())
                    .orElse(errors.getFirst());
            int code = worst.path("code").asInt();
            String text = "上游 " + code + " " + worst.path("msg").asString("") + "（field=" + worst.path("field").asString("")
                    + "，HTTP " + status + "，requestId=" + requestId + "）";
            return switch (QuadcellErrors.classify(code)) {
                case REJECTED -> Outcome.rejected(String.valueOf(code), text);
                case RETRYABLE -> Outcome.retryable(text, null);
                case UNKNOWN -> Outcome.unknown(text);
            };
        }
        String text = "HTTP " + status + " requestId=" + requestId;
        if (status == 401 || status == 403) {
            return Outcome.rejected("AUTH_FAILED", "鉴权失败：检查 API key 与出口 IP 白名单（" + text + "）");
        }
        if (status == 503) {
            return Outcome.retryable(text, null);
        }
        if (status >= 500) {
            return write ? Outcome.unknown(text) : Outcome.retryable(text, null);
        }
        return Outcome.rejected("HTTP_" + status, text);
    }

    private Duration retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After").map(String::strip).filter(v -> v.matches("\\d{1,5}"))
                .map(v -> Duration.ofSeconds(Long.parseLong(v))).orElse(properties.getThrottleRetryAfter());
    }

    private static List<JsonNode> errors(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode errors = JSON.readTree(body).path("errors");
            List<JsonNode> list = new ArrayList<>();
            if (errors.isArray()) {
                errors.values().stream().filter(e -> e.path("code").isNumber()).forEach(list::add);
            }
            return list;
        } catch (JacksonException e) {
            return List.of();
        }
    }

    private static boolean causedByConnect(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    private String baseUrl() {
        String base = properties.getBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String profilePath(String iccid) {
        return "/sim-profile/" + iccid;
    }

    private static boolean validCard(CardRef card) {
        return card != null && card.iccid() != null && CARD_ID.matcher(card.iccid()).matches();
    }

    // ---- 报文 ----

    private static Optional<String> put(ObjectNode target, String key, FieldType type, String value) {
        String v = value == null ? "" : value.strip();
        switch (type) {
            case STRING -> target.put(key, v);
            case INT -> {
                if (!v.matches("-?\\d{1,9}")) {
                    return Optional.of(key + " 须为整数");
                }
                target.put(key, Integer.parseInt(v));
            }
            case BOOL -> {
                if (!v.equals("true") && !v.equals("false")) {
                    return Optional.of(key + " 须为 true/false");
                }
                target.put(key, Boolean.parseBoolean(v));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> packId(JsonNode json) {
        JsonNode id = json.path("packId");
        return id.isString() && !id.stringValue().isBlank() ? Optional.of(id.stringValue()) : Optional.empty();
    }

    private static String firstPackId(JsonNode json) {
        JsonNode packs = json.path("packs");
        return packs.isArray() && !packs.isEmpty() ? packId(packs.get(0)).orElse(null) : null;
    }

    private Outcome<UsageSnapshot> snapshot(JsonNode json, String cardUpstreamRef) {
        if (!json.path("status").isNumber()) {
            return Outcome.unknown("查档响应缺 status");
        }
        int code = json.path("status").asInt();
        CardStatus status = switch (code) {
            case 2, 3 -> CardStatus.INACTIVE;
            case ACTIVATED -> CardStatus.ACTIVE;
            case SUSPENDED -> CardStatus.SUSPENDED;
            case 6 -> CardStatus.EXPIRED;
            default -> null;
        };
        if (status == null) {
            return Outcome.unknown("未登记的卡状态码 " + code);
        }
        List<PlanUsage> plans = new ArrayList<>();
        if (json.path("packs").isArray()) {
            json.path("packs").values().forEach(pack -> plans.add(plan(pack)));
        }
        return Outcome.ok(new UsageSnapshot(cardUpstreamRef, status, plans, masked(json).toString()));
    }

    private PlanUsage plan(JsonNode pack) {
        Long quota = bytes(number(pack, "iQuota"));
        Long remaining = bytes(number(pack, "rQuota"));
        Long used = quota != null && remaining != null ? quota - remaining : null;
        String startsRaw = text(pack, "actDate");
        String expiresRaw = text(pack, "expDate");
        return new PlanUsage(packId(pack).orElse(null), quota, used, remaining, null, null, null, null, null, null, time(startsRaw), time(expiresRaw),
                startsRaw, expiresRaw);
    }

    private Long bytes(Double megabytes) {
        return megabytes == null ? null
                : BigDecimal.valueOf(megabytes).multiply(BigDecimal.valueOf(properties.getBytesPerMb())).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private Instant time(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException withoutOffset) {
            return LocalDateTime.parse(s).atZone(ZoneId.of(properties.getTimeZone())).toInstant();
        }
    }

    private static Double number(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isNumber() ? n.doubleValue() : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isString() ? n.stringValue() : null;
    }

    /** 原始报文入库前把卡号类字段只留后 4 位。 */
    private static JsonNode masked(JsonNode json) {
        JsonNode copy = json.deepCopy();
        mask(copy);
        return copy;
    }

    private static void mask(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (String field : List.copyOf(object.propertyNames())) {
                JsonNode value = object.get(field);
                if (MASKED_FIELDS.contains(field) && value.isString()) {
                    object.put(field, last4(value.stringValue()));
                } else if (MASKED_FIELDS.contains(field) && value instanceof ArrayNode array) {
                    for (int i = 0; i < array.size(); i++) {
                        if (array.get(i).isString()) {
                            array.set(i, JSON.getNodeFactory().stringNode(last4(array.get(i).stringValue())));
                        }
                    }
                } else {
                    mask(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.values().forEach(QuadcellAdapter::mask);
        }
    }

    private static String last4(String value) {
        String v = value.strip();
        return v.length() <= 4 ? "****" : "****" + v.substring(v.length() - 4);
    }
}
