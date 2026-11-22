package com.liroam.fulfillment.credential;

import java.time.Clock;

import org.springframework.context.ApplicationEventPublisher;

import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.crypto.SensitiveCipher.Sealed;
import com.liroam.common.crypto.SensitiveField;
import com.liroam.fulfillment.CredentialDelivered;
import com.liroam.fulfillment.store.CardCredentials;
import com.liroam.supplier.spi.CredentialResult;
import com.liroam.supplier.spi.SupplierCapabilities.CredentialChannel;

/**
 * 三通道交码的唯一落库点：写 {@code lr_card_credential}（激活码加密；实体卡记物流）后在同一事务内发布 {@link CredentialDelivered}；
 * 卡状态、履约行、壳发货回写都只订阅事件推进。调用方须已开事务。
 */
public class CredentialDeliveries {

    private final CardCredentials credentials;
    private final SensitiveCipher cipher;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CredentialDeliveries(CardCredentials credentials, SensitiveCipher cipher, ApplicationEventPublisher events, Clock clock) {
        this.credentials = credentials;
        this.cipher = cipher;
        this.events = events;
        this.clock = clock;
    }

    public void deliver(long cardAssetId, CredentialResult.Kind kind, String payload, CredentialChannel channel, String operatorId) {
        deliverSealed(cardAssetId, kind, cipher.seal(SensitiveField.CREDENTIAL, payload), channel, operatorId);
    }

    public void deliverSealed(long cardAssetId, CredentialResult.Kind kind, Sealed payload, CredentialChannel channel, String operatorId) {
        credentials.insert(cardAssetId, kind.name(), payload, channel.name(), operatorId, clock.instant());
        events.publishEvent(new CredentialDelivered(cardAssetId, channel));
    }

    public void deliverShipment(long cardAssetId, Shipment shipment, CredentialChannel channel, String operatorId) {
        credentials.insertShipment(cardAssetId, shipment.logisticsRef(), shipment.logisticsCompany(), shipment.logisticsNo(), channel.name(),
                operatorId, clock.instant());
        events.publishEvent(new CredentialDelivered(cardAssetId, channel));
    }

    /** 开通前就已到的交码（如导入早于开通）：卡开通后补发事件，让卡与履约行继续推进。 */
    public boolean announceExisting(long cardAssetId) {
        return credentials.current(cardAssetId).map(c -> {
            events.publishEvent(new CredentialDelivered(cardAssetId, CredentialChannel.valueOf(c.channel())));
            return true;
        }).orElse(false);
    }
}
