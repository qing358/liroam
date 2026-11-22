package com.liroam.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.liroam.common.safety.Simulated;
import com.tngtech.archunit.lang.ArchRule;

/** domain-design §14「架构检查」。新增规则须同时在 {@code RulesCatchViolationsTest} 配一个违例夹具。 */
final class LiroamRules {

    /** 厂商 / 渠道实现与模拟实现所在包；业务代码不得依赖。 */
    static final String[] VENDOR_PACKAGES = {
            "com.liroam.supplier.adapter..", "com.liroam.supplier.mock..", "com.liroam.channel.adapter..", "com.liroam.channel.mock.."};

    private LiroamRules() {
    }

    static final ArchRule ONLY_SHELL_BRIDGE_DEPENDS_ON_LILISHOP = noClasses()
            .that().resideInAPackage("com.liroam..").and().resideOutsideOfPackage("com.liroam.shell..")
            .should().dependOnClassesThat().resideInAPackage("cn.lili..")
            .because("壳只经 liroam-shell-bridge 防腐层挂接（§5）");

    static final ArchRule BUSINESS_DOES_NOT_DEPEND_ON_VENDOR_OR_MOCK = noClasses()
            .that().resideInAPackage("com.liroam..").and().resideOutsideOfPackages(VENDOR_PACKAGES)
            .should().dependOnClassesThat().resideInAnyPackage(VENDOR_PACKAGES)
            .because("上层只按能力声明分支，不按供应商/渠道分支");

    /** CT-SUP-09。 */
    static final ArchRule SUPPLIER_CODE_HAS_NO_STORAGE = noClasses()
            .that().resideInAPackage("com.liroam.supplier..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.springframework.transaction..",
                    "com.baomidou..", "org.apache.ibatis..", "com.liroam.catalog..", "com.liroam.fulfillment..")
            .because("适配器只翻译上游调用，不读写台账（CT-SUP-09）");

    static final ArchRule ONLY_FULFILLMENT_USES_LEDGER_STORE = classes()
            .that().resideInAPackage("com.liroam.fulfillment.store..")
            .should().onlyHaveDependentClassesThat().resideInAPackage("com.liroam.fulfillment..")
            .because("卡资产只经唯一履约入口写入");

    static final ArchRule SIMULATED_ONLY_IN_MOCK_PACKAGES = classes()
            .that().areAnnotatedWith(Simulated.class)
            .should().resideInAnyPackage("com.liroam.supplier.mock..", "com.liroam.channel.mock..")
            .allowEmptyShould(true)
            .because("模拟实现集中在 mock 包，生产闸按 @Simulated 拦截");
}
