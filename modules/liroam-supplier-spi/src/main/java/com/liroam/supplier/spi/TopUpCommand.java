package com.liroam.supplier.spi;

import java.util.Map;

public record TopUpCommand(String requestNo, CardRef card, String offeringCode, Map<String, String> offeringPayload) {

    public TopUpCommand {
        offeringPayload = offeringPayload == null ? Map.of() : Map.copyOf(offeringPayload);
    }
}
