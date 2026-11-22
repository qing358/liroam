package com.liroam.supplier.spi;

import java.util.Optional;

/**
 * 供应商适配契约（domain-design §6.1）。
 * <p>
 * 实现放 {@code com.liroam.supplier.adapter.<供应商>}，Bean 名即 {@code lr_supplier.adapter_key}。
 * 适配器只把领域请求翻译成上游调用并归一结果：不读写台账、不重试、不抛业务异常——
 * 一切结果用 {@link Outcome} 四态返回，由指令 worker 决定重试、查档或转人工。
 * 新供应商接入 = 写适配器 + 套餐映射配置 + 通过 {@code SupplierAdapterContractTest}。
 */
public interface SupplierAdapter {

    /** 代码内默认能力；{@code lr_supplier.capability_overrides_json} 可再收窄。 */
    SupplierCapabilities capabilities();

    /** 开卡/订购。池模式带 ICCID；下单即建模式由上游返回 ICCID。 */
    Outcome<ProvisionResult> provision(ProvisionCommand command);

    /** API 交码；能力未声明 API 交码时返回 {@link Outcome.Rejected}。 */
    Outcome<CredentialResult> fetchCredential(CardRef card);

    /** 状态与各套餐额度/到期，已换算为字节/秒/条并保留上游原值。 */
    Outcome<UsageSnapshot> queryUsage(CardRef card);

    /** 加包：映射到该供应商的套餐码。 */
    Outcome<TopUpResult> addPlan(TopUpCommand command);

    /** 停用/恢复/终止；能力未声明的操作返回 {@link Outcome.Rejected}。 */
    Outcome<StatusResult> changeStatus(StatusCommand command);

    /** 查档：写操作结果未知时判定是否已生效。 */
    Outcome<LookupResult> lookup(LookupQuery query);

    /** 声明了 webhook 的供应商返回回调解析器（含验签）。 */
    default Optional<WebhookParser> webhook() {
        return Optional.empty();
    }
}
