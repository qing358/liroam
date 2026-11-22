package com.liroam.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;

/** 对全部自研生产代码（各模块主 jar，不含 test-jar 与测试类）执行架构规则。 */
class ArchitectureTest {

    /** 只导入各模块主产物：排除 test-classes 目录与 *-tests.jar（test-jar 里有契约测试夹具与桩）。 */
    static final ImportOption PRODUCTION_ONLY = (Location location) ->
            !location.contains("/test-classes/") && !location.contains("-tests.jar");

    private static JavaClasses production;

    @BeforeAll
    static void importProduction() {
        production = new ClassFileImporter().withImportOption(PRODUCTION_ONLY).importPackages("com.liroam");
    }

    @Test
    void importsEveryModule() {
        Set<String> roots = production.stream().map(JavaClass::getPackageName)
                .map(p -> p.split("\\.").length > 2 ? p.split("\\.")[2] : p).collect(Collectors.toSet());
        assertThat(roots).contains("common", "schema", "supplier", "catalog", "fulfillment", "shell");
        assertThat(production.stream().map(JavaClass::getName)).noneMatch(n -> n.contains(".contract."));
    }

    @Test
    void onlyShellBridgeDependsOnLilishop() {
        LiroamRules.ONLY_SHELL_BRIDGE_DEPENDS_ON_LILISHOP.check(production);
    }

    @Test
    void businessDoesNotDependOnVendorOrMock() {
        LiroamRules.BUSINESS_DOES_NOT_DEPEND_ON_VENDOR_OR_MOCK.check(production);
    }

    @Test
    void ct_sup_09_supplierCodeHasNoStorage() {
        LiroamRules.SUPPLIER_CODE_HAS_NO_STORAGE.check(production);
    }

    @Test
    void onlyFulfillmentUsesLedgerStore() {
        LiroamRules.ONLY_FULFILLMENT_USES_LEDGER_STORE.check(production);
    }

    @Test
    void simulatedOnlyInMockPackages() {
        LiroamRules.SIMULATED_ONLY_IN_MOCK_PACKAGES.check(production);
    }
}
