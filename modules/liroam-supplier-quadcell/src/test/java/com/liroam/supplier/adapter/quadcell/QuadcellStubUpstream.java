package com.liroam.supplier.adapter.quadcell;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.contract.UpstreamFixture;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 桩上游：按 Open API v1.3.3 的路径、请求头、报文与 §7 错误码回放，状态校验照文档「驗證」一栏。
 * 适配器每次调用读 {@link QuadcellProperties#getBaseUrl()}，连接失败靠临时指向一个已关闭的端口模拟。
 */
public final class QuadcellStubUpstream implements UpstreamFixture, AutoCloseable {

    public static final String API_KEY = "stub-api-key-not-a-secret";
    public static final String PREFIX = "/open-api/1.0";
    public static final String BASE_PACK = "DATAPKG01";
    public static final String TOPUP_PACK = "DATAPKG02";
    static final Duration READ_TIMEOUT = Duration.ofMillis(400);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, Double> PACK_QUOTA_MB = Map.of(BASE_PACK, 3072.0, TOPUP_PACK, 1024.0);
    private static final Pattern ROUTE = Pattern.compile("^" + PREFIX + "/sim-profile/([^/]+)(/package|/suspend|/resume)?$");

    public record Recorded(String method, String path, Map<String, String> headers, String body) {
        public JsonNode json() {
            return JSON.readTree(body);
        }
    }

    record Scripted(int status, Map<String, String> headers, String body) {
    }

    static final class Profile {
        volatile int status = 2;
        final List<ObjectNode> packs = new CopyOnWriteArrayList<>();
    }

    private final HttpServer server;
    private final QuadcellProperties properties;
    private final String liveBaseUrl;
    private final Map<String, Profile> profiles = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Failure> nextFailure = new AtomicReference<>();
    private final AtomicReference<Scripted> nextScripted = new AtomicReference<>();
    private final AtomicLong seq = new AtomicLong();

    private QuadcellStubUpstream(HttpServer server, QuadcellProperties properties) {
        this.server = server;
        this.properties = properties;
        this.liveBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + PREFIX;
        properties.setBaseUrl(liveBaseUrl);
    }

    public static QuadcellStubUpstream start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "quadcell-stub");
                t.setDaemon(true);
                return t;
            }));
            QuadcellProperties properties = new QuadcellProperties();
            properties.setApiKey(API_KEY);
            properties.setDefaultMode(1);
            properties.setReadTimeout(READ_TIMEOUT);
            properties.setConnectTimeout(Duration.ofSeconds(2));
            QuadcellStubUpstream stub = new QuadcellStubUpstream(server, properties);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public QuadcellProperties properties() {
        return properties;
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public Recorded lastRequest() {
        return requests.getLast();
    }

    /** 下一次请求原样回这段响应，不动上游状态。 */
    void script(int status, Map<String, String> headers, String body) {
        nextScripted.set(new Scripted(status, headers, body));
    }

    public int status(String iccid) {
        return profiles.get(iccid).status;
    }

    void setStatus(String iccid, int status) {
        profiles.get(iccid).status = status;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- UpstreamFixture ----

    @Override
    public void reset() {
        profiles.clear();
        requests.clear();
        nextFailure.set(null);
        nextScripted.set(null);
        properties.setBaseUrl(liveBaseUrl);
    }

    @Override
    public String idleIccid() {
        String iccid = String.format("8985200%012d", seq.incrementAndGet());
        profiles.put(iccid, new Profile());
        return iccid;
    }

    @Override
    public void failNext(Failure failure) {
        if (failure == Failure.CONNECT_FAILED) {
            properties.setBaseUrl("http://127.0.0.1:" + closedPort() + PREFIX);
            return;
        }
        nextFailure.set(failure);
    }

    /** 上游不收请求号，无从按请求号计数；声明了幂等键的适配器才会用到。 */
    @Override
    public int effectiveProvisions(String requestNo) {
        throw new UnsupportedOperationException("上游不收请求号");
    }

    @Override
    public void activate(CardRef card) {
        profiles.get(card.iccid()).status = 4;
    }

    @Override
    public ExpectedUsage seedUsage(CardRef card) {
        ObjectNode pack = profiles.get(card.iccid()).packs.getFirst();
        pack.put("iQuota", 3072.0);
        pack.put("rQuota", 1024.5);
        pack.put("actDate", " 2026-11-20T10:00:00+08:00");
        pack.put("expDate", "2026-12-01T23:59:59+08:00");
        pack.put("status", 2);
        return new ExpectedUsage(Math.round(1024.5 * 1024 * 1024), java.time.Instant.parse("2026-12-01T15:59:59Z"), "2026-12-01T23:59:59+08:00");
    }

    @Override
    public String baseOfferingCode() {
        return BASE_PACK;
    }

    @Override
    public String topUpOfferingCode() {
        return TOPUP_PACK;
    }

    @Override
    public SignedCallback validCallback(CardRef card) {
        throw new UnsupportedOperationException("上游无回调");
    }

    @Override
    public SignedCallback tamperedCallback(CardRef card) {
        throw new UnsupportedOperationException("上游无回调");
    }

    // ---- HTTP ----

    private void handle(HttpExchange ex) throws IOException {
        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> headers = new ConcurrentHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
        String path = ex.getRequestURI().getPath();
        requests.add(new Recorded(ex.getRequestMethod(), path, Map.copyOf(headers), body));

        Scripted scripted = nextScripted.getAndSet(null);
        if (scripted != null) {
            scripted.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            send(ex, scripted.status(), scripted.body());
            return;
        }
        if (!API_KEY.equals(headers.get("x-api-key"))) {
            send(ex, 401, "");
            return;
        }
        Failure failure = nextFailure.getAndSet(null);
        if (failure != null) {
            switch (failure) {
                case THROTTLED -> {
                    send(ex, 429, "");
                    return;
                }
                case SERVER_ERROR -> {
                    send(ex, 503, "");
                    return;
                }
                case BAD_REQUEST -> {
                    send(ex, 400, error(1000, "mode", "Invalid value", path));
                    return;
                }
                case UNREGISTERED_CODE -> {
                    send(ex, 400, error(7777, "", "Something new", path));
                    return;
                }
                default -> {
                }
            }
        }
        Reply reply = route(ex.getRequestMethod(), path, body);
        if (failure == Failure.TIMEOUT_AFTER_SEND) {
            sleep(READ_TIMEOUT.multipliedBy(4));
        }
        ex.getResponseHeaders().add("requestId", "req-" + seq.incrementAndGet());
        send(ex, reply.status(), reply.body());
    }

    private record Reply(int status, String body) {
    }

    private Reply route(String method, String path, String body) {
        Matcher m = ROUTE.matcher(path);
        if (!m.matches()) {
            return new Reply(404, "");
        }
        String id = m.group(1);
        String action = m.group(2) == null ? "" : m.group(2);
        Profile p = profiles.get(id);
        if (p == null) {
            return new Reply(404, error(1060, "iccid", "No record found", path));
        }
        JsonNode req = body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
        return switch (method + " " + action) {
            case "GET " -> new Reply(200, detail(id, p));
            case "POST " -> addSubscription(p, req, path);
            case "POST /package" -> addPackage(p, req, path);
            case "POST /suspend" -> transition(p, 4, 5, p.status == 5 ? 2006 : 2004, path);
            case "POST /resume" -> transition(p, 5, 4, 1000, path);
            case "DELETE " -> terminate(p, path);
            default -> new Reply(404, "");
        };
    }

    private Reply addSubscription(Profile p, JsonNode req, String path) {
        if (p.status != 2) {
            return new Reply(400, error(2002, "iccid", "SIM profile has added subscription already", path));
        }
        if (!req.path("mode").isNumber()) {
            return new Reply(400, error(1001, "mode", "Input value is required", path));
        }
        String packCode = req.path("pack").path("packCode").asString("");
        if (!PACK_QUOTA_MB.containsKey(packCode)) {
            return new Reply(400, error(1060, "packCode", "No record found", path));
        }
        p.status = 3;
        return new Reply(200, packIdBody(p, packCode));
    }

    private Reply addPackage(Profile p, JsonNode req, String path) {
        if (p.status != 3 && p.status != 4) {
            int code = switch (p.status) {
                case 2 -> 2003;
                case 5 -> 2006;
                default -> 2007;
            };
            return new Reply(400, error(code, "iccid", "SIM profile state", path));
        }
        String packCode = req.path("packCode").asString("");
        if (!PACK_QUOTA_MB.containsKey(packCode)) {
            return new Reply(400, error(1060, "packCode", "No record found", path));
        }
        return new Reply(200, packIdBody(p, packCode));
    }

    private Reply transition(Profile p, int from, int to, int code, String path) {
        if (p.status != from) {
            return new Reply(400, error(code, "iccid", "SIM profile state", path));
        }
        p.status = to;
        return new Reply(200, "");
    }

    private Reply terminate(Profile p, String path) {
        if (p.status != 3 && p.status != 4 && p.status != 5) {
            return new Reply(400, error(2003, "iccid", "SIM profile does not add subscription yet", path));
        }
        p.status = 2;
        p.packs.clear();
        return new Reply(200, "");
    }

    private String packIdBody(Profile p, String packCode) {
        String packId = String.format("69ba6aec%016x", seq.incrementAndGet());
        ObjectNode pack = JSON.createObjectNode();
        pack.put("packId", packId);
        pack.put("packCode", packCode);
        pack.put("iQuota", PACK_QUOTA_MB.get(packCode));
        pack.put("rQuota", PACK_QUOTA_MB.get(packCode));
        pack.putNull("actDate");
        pack.putNull("expDate");
        pack.put("priority", "M");
        pack.put("status", 1);
        pack.put("statusDesc", "Not Activate");
        p.packs.add(pack);
        return JSON.createObjectNode().put("packId", packId).toString();
    }

    private static String detail(String iccid, Profile p) {
        ObjectNode d = JSON.createObjectNode();
        d.put("imsi", "454120000" + iccid.substring(iccid.length() - 6));
        d.put("iccid", iccid);
        d.put("msisdn", "85290" + iccid.substring(iccid.length() - 6));
        d.putArray("imeis").add("3567890123" + iccid.substring(iccid.length() - 5));
        d.put("resType", "45412");
        d.put("svcCode", "PROF01");
        d.put("mode", 1);
        d.put("status", p.status);
        d.put("statusDesc", switch (p.status) {
            case 2 -> "Idle";
            case 3 -> "Provisioned";
            case 4 -> "Activated";
            case 5 -> "Suspended";
            default -> "Expired";
        });
        ArrayNode packs = d.putArray("packs");
        new ArrayList<>(p.packs).forEach(pack -> packs.add(pack.deepCopy()));
        return d.toString();
    }

    static String error(int code, String field, String msg, String path) {
        ObjectNode e = JSON.createObjectNode();
        e.put("requestId", "6a150e23a896971e4e2f4e14");
        e.put("timestamp", 1774258044757L);
        e.putArray("errors").addObject().put("field", field).put("code", code).put("msg", msg);
        e.put("path", path);
        return e.toString();
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
