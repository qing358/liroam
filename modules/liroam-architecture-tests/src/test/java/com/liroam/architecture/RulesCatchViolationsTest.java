package com.liroam.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.liroam.archfixture.bad.LeaksLilishop;
import com.liroam.archfixture.bad.SimulatedOutsideMock;
import com.liroam.archfixture.bad.TouchesLedgerStore;
import com.liroam.archfixture.bad.UsesVendorAdapter;
import com.liroam.supplier.adapter.archfixture.FakeVendorAdapter;
import com.liroam.supplier.archfixture.AdapterWithStorage;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

/** 每条规则对一个故意违例的夹具类必须报错，证明规则不是空转。 */
class RulesCatchViolationsTest {

    private static void assertCatches(ArchRule rule, Class<?>... fixture) {
        assertThatThrownBy(() -> rule.check(new ClassFileImporter().importClasses(fixture))).isInstanceOf(AssertionError.class);
    }

    @Test
    void lilishopDependencyOutsideShellIsCaught() {
        assertCatches(LiroamRules.ONLY_SHELL_BRIDGE_DEPENDS_ON_LILISHOP, LeaksLilishop.class);
    }

    @Test
    void vendorDependencyFromBusinessCodeIsCaught() {
        assertCatches(LiroamRules.BUSINESS_DOES_NOT_DEPEND_ON_VENDOR_OR_MOCK, UsesVendorAdapter.class, FakeVendorAdapter.class);
    }

    @Test
    void storageInSupplierCodeIsCaught() {
        assertCatches(LiroamRules.SUPPLIER_CODE_HAS_NO_STORAGE, AdapterWithStorage.class);
    }

    @Test
    void ledgerStoreUseOutsideFulfillmentIsCaught() {
        assertCatches(LiroamRules.ONLY_FULFILLMENT_USES_LEDGER_STORE, TouchesLedgerStore.class, com.liroam.fulfillment.store.CardAssets.class);
    }

    @Test
    void simulatedOutsideMockIsCaught() {
        assertCatches(LiroamRules.SIMULATED_ONLY_IN_MOCK_PACKAGES, SimulatedOutsideMock.class);
    }
}
