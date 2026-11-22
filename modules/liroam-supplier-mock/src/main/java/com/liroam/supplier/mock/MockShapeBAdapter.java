package com.liroam.supplier.mock;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.liroam.common.safety.Simulated;
import com.liroam.supplier.mock.MockUpstream.WirePlan;
import com.liroam.supplier.spi.CredentialResult;
import com.liroam.supplier.spi.Outcome;
import com.liroam.supplier.spi.ProvisionCommand;
import com.liroam.supplier.spi.ProvisionResult;
import com.liroam.supplier.spi.SupplierCapabilities;
import com.liroam.supplier.spi.SupplierCapabilities.CredentialChannel;
import com.liroam.supplier.spi.SupplierCapabilities.InventoryMode;
import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;
import com.liroam.supplier.spi.SupplierCapabilities.TopUpMode;
import com.liroam.supplier.spi.SupplierCapabilities.UsageDimension;
import com.liroam.supplier.spi.UsageSnapshot;
import com.liroam.supplier.spi.UsageSnapshot.CardStatus;
import com.liroam.supplier.spi.WebhookParser;

/**
 * 模拟供应商形状 B：下单即建卡、开卡同步返回 ICCID 与 LPA（也可 API 再取）、透传幂等键、HMAC 回调、
 * 只能终止、加包为同卡新订阅、600 次/分钟；上游用字节与 ISO-8601，另报语音秒与短信条。
 * <p>
 * 回调：{@code application/x-www-form-urlencoded}，字段 {@code card_ref, plan_ref, status, quota_bytes, used_bytes,
 * voice_seconds, sms, starts_at, expires_at}（语音秒、短信条为额度；可选 {@code voice_used_seconds, sms_used} 已用，缺省 0）；头 {@value #SIGNATURE_HEADER} = hex(HMAC-SHA256(密钥, 原始请求体))。
 */
@Simulated
public final class MockShapeBAdapter extends AbstractMockAdapter {

    public static final String ADAPTER_KEY = "mock-shape-b";
    public static final String SIGNATURE_HEADER = "X-Mock-Signature";

    static final SupplierCapabilities CAPABILITIES = new SupplierCapabilities(
            InventoryMode.CREATE_ON_ORDER,
            Set.of(CredentialChannel.API),
            Set.of(UsageDimension.DATA, UsageDimension.VOICE, UsageDimension.SMS),
            Set.of(StatusOp.TERMINATE),
            TopUpMode.NEW_SUBSCRIPTION,
            false, true, true, 600);

    private final byte[] webhookSecret;

    public MockShapeBAdapter(MockFaults faults, Clock clock, String webhookSecret) {
        super(new MockUpstream(ADAPTER_KEY, "MB", WireFormat.BYTES_ISO, true, faults, clock));
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("mock-shape-b 回调密钥不能为空");
        }
        this.webhookSecret = webhookSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String adapterKey() {
        return ADAPTER_KEY;
    }

    @Override
    public SupplierCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    Duration throttleRetryAfter() {
        return Duration.ofMillis(200);
    }

    @Override
    public Outcome<ProvisionResult> provision(ProvisionCommand command) {
        Optional<MockPackage> pkg = MockPackage.parse(command.offeringCode());
        if (pkg.isEmpty()) {
            return badPackage(command.offeringCode());
        }
        return call(() -> upstream.provision(command.requestNo(), null, pkg.get()), card -> new ProvisionResult(card.iccid(), card.ref(),
                Optional.of(new CredentialResult(CredentialResult.Kind.LPA, MockUpstream.lpa(card.ref())))));
    }

    @Override
    public Optional<WebhookParser> webhook() {
        return Optional.of(this::parseCallback);
    }

    private Outcome<UsageSnapshot> parseCallback(Map<String, String> headers, byte[] body) {
        String signature = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(SIGNATURE_HEADER))
                .map(Map.Entry::getValue).findFirst().orElse("").toLowerCase(Locale.ROOT);
        if (!MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8), sign(body).getBytes(StandardCharsets.UTF_8))) {
            return Outcome.rejected("BAD_SIGNATURE", "回调验签失败");
        }
        try {
            Map<String, String> fields = form(new String(body, StandardCharsets.UTF_8));
            CardStatus status = upstream.wire().status(required(fields, "status"));
            if (status == null) {
                return Outcome.rejected("BAD_PAYLOAD", "回调状态码未登记：" + fields.get("status"));
            }
            WirePlan plan = new WirePlan(required(fields, "plan_ref"), Long.parseLong(required(fields, "quota_bytes")),
                    Long.parseLong(required(fields, "used_bytes")), Long.parseLong(required(fields, "voice_seconds")),
                    Long.parseLong(fields.getOrDefault("voice_used_seconds", "0")), Integer.parseInt(required(fields, "sms")),
                    Integer.parseInt(fields.getOrDefault("sms_used", "0")), required(fields, "starts_at"), required(fields, "expires_at"));
            return Outcome.ok(new UsageSnapshot(required(fields, "card_ref"), status, List.of(plan(plan)), Json.write(fields)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return Outcome.rejected("BAD_PAYLOAD", "回调内容不完整或格式不对");
        }
    }

    private static Map<String, String> form(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            fields.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length == 2 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return fields;
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(key);
        }
        return value;
    }

    private String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
