package com.liroam.fulfillment.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.CredentialDelivered;
import com.liroam.fulfillment.card.CardQueries;
import com.liroam.fulfillment.card.CardQueries.CredentialView;
import com.liroam.fulfillment.delivery.CardShipments;
import com.liroam.fulfillment.delivery.CardShipments.OrderCard;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.RecordingCredentialEvents;
import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.supplier.spi.SupplierCapabilities.CredentialChannel;

/** 实体卡简化履约：运营在卡上录物流（SHIPMENT 交码，与激活码同一事件）；实体卡不交激活码、eSIM 不录物流；整单回写读同一份物流。 */
class ShipmentTest {

    private static FulfillmentHarness h;
    private static TestCatalog c;
    private static CredentialEntry entry;

    private static final Shipment SF = new Shipment("LOG-SF", "顺丰速运", "SF1234567890");

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("fulfillment_shipment");
        c = h.catalog;
        entry = h.bean(CredentialEntry.class);
    }

    @AfterAll
    static void stop() {
        h.close();
    }

    @BeforeEach
    void reset() {
        h.reset();
    }

    @Test
    void shipmentDeliversPhysicalCardAndBuyerSeesLogisticsInsteadOfCode() {
        h.importPoolA(1, true, "PHYSICAL");
        long card = h.acceptCard("O-1", c.skuAPhysical, "m1");
        h.drain();
        assertThat(h.status("lr_card_asset", card)).as("池卡随批交码对实体卡不生效，等物流").isEqualTo("PROVISIONED");

        entry.enterShipment(card, SF, "op1");
        h.drain();

        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
        assertThat(h.bean(RecordingCredentialEvents.class).events()).containsExactly(new CredentialDelivered(card, CredentialChannel.MANUAL));
        Map<String, Object> row = h.jdbc.queryForMap("SELECT kind, logistics_ref, logistics_company, logistics_no, payload_cipher FROM lr_card_credential "
                + "WHERE card_asset_id = ?", card);
        assertThat(row).containsEntry("kind", "SHIPMENT").containsEntry("logistics_ref", "LOG-SF").containsEntry("logistics_company", "顺丰速运")
                .containsEntry("logistics_no", "SF1234567890").containsEntry("payload_cipher", null);
        CredentialView view = h.bean(CardQueries.class).credentialForMember(card, "m1").orElseThrow();
        assertThat(view).extracting(CredentialView::kind, CredentialView::payload, CredentialView::logisticsCompany, CredentialView::logisticsNo)
                .containsExactly("SHIPMENT", null, "顺丰速运", "SF1234567890");
        assertThat(h.shell.calls()).containsExactly("O-1");
    }

    @Test
    void apiSupplierDoesNotFetchActivationCodeForPhysicalCards() {
        long card = h.acceptCard("O-2", c.skuBPhysical, "m1");
        h.drain();

        assertThat(h.status("lr_card_asset", card)).isEqualTo("PROVISIONED");
        assertThat(h.count("lr_supplier_command WHERE type = 'FETCH_CREDENTIAL'")).isZero();
        assertThat(h.count("lr_card_credential")).isZero();
    }

    @Test
    void formDecidesWhichCredentialIsAccepted() {
        h.importPoolA(1, false);
        h.importPoolA(1, false, "PHYSICAL");
        long esim = h.acceptCard("O-3", c.skuANew, "m1");
        long sim = h.acceptCard("O-4", c.skuAPhysical, "m1");
        h.drain();

        assertThatThrownBy(() -> entry.enterShipment(esim, SF, "op1")).isInstanceOf(UserFacingException.class).hasMessage("eSIM 卡无需物流，请录入激活码");
        assertThatThrownBy(() -> entry.enter(sim, "LPA:1$x$", "op1")).isInstanceOf(UserFacingException.class).hasMessage("实体卡请录入物流单号");
        assertThatThrownBy(() -> entry.enterShipment(sim, new Shipment("LOG-SF", "顺丰速运", " "), "op1")).isInstanceOf(UserFacingException.class)
                .hasMessage("请选择物流公司并填写运单号");
        assertThat(h.count("lr_card_credential")).isZero();
    }

    @Test
    void importSkipsPhysicalCards() {
        List<String> iccids = h.importPoolA(1, false, "PHYSICAL");
        long sim = h.acceptCard("O-5", c.skuAPhysical, "m1");
        h.drain();

        CredentialImports.ImportReport report = h.bean(CredentialImports.class).importCsv(new StringReader(iccids.getFirst() + ",LPA:1$x$"), "op1");

        assertThat(report.delivered()).isZero();
        assertThat(report.unmatched()).extracting(CredentialImports.Unmatched::lineNo, CredentialImports.Unmatched::reason)
                .containsExactly(tuple(1, "实体卡请录入物流单号"));
        assertThat(h.status("lr_card_asset", sim)).isEqualTo("PROVISIONED");
    }

    @Test
    void orderCardsListFormAndCurrentShipmentForShellShipping() {
        h.importPoolA(1, false, "PHYSICAL");
        long sim = h.acceptCard("O-6", c.skuAPhysical, "m1");
        h.acceptShop("O-6", "O-6-2", c.skuBNew, 1, "m1");
        h.drain();
        entry.enterShipment(sim, new Shipment("LOG-YT", "圆通速递", "YT0001"), "op1");
        entry.enterShipment(sim, SF, "op1");

        List<OrderCard> cards = h.bean(CardShipments.class).forOrder("O-6");

        assertThat(cards).extracting(OrderCard::form, OrderCard::logisticsRef, OrderCard::logisticsNo)
                .containsExactlyInAnyOrder(tuple("PHYSICAL", "LOG-SF", "SF1234567890"), tuple("ESIM", null, null));
    }
}
