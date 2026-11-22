package com.liroam.fulfillment.store;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.json.JsonParserFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 套餐映射（{@code lr_supplier_offering}）里下发上游要用的套餐码与额外参数。 */
public class Offerings {

    public record Offering(String code, Map<String, String> payload) {
    }

    record Row(String upstreamPackageCode, String upstreamPayloadJson) {
    }

    private final JdbcClient jdbc;

    public Offerings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Offering get(long offeringId) {
        Row row = jdbc.sql("SELECT upstream_package_code, upstream_payload_json FROM lr_supplier_offering WHERE id = ?").param(offeringId)
                .query(Row.class).optional().orElseThrow(() -> new IllegalStateException("套餐映射 " + offeringId + " 不存在"));
        Map<String, String> payload = row.upstreamPayloadJson() == null ? Map.of()
                : JsonParserFactory.getJsonParser().parseMap(row.upstreamPayloadJson()).entrySet().stream()
                        .filter(e -> e.getValue() != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));
        return new Offering(row.upstreamPackageCode(), payload);
    }
}
