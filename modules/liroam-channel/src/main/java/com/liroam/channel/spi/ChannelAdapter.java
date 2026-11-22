package com.liroam.channel.spi;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 渠道入单契约（domain-design §11）。实现只做渠道报文 ↔ 领域对象的翻译，不读写台账；Bean 名 = {@code lr_channel.adapter_key}。
 * 渠道字段（订单号格式、金额单位、时区、收货人结构）只留在实现里。
 */
public interface ChannelAdapter {

    /** 拉取下单时间（或更新时间）不早于 {@code since} 的已付款订单；重复返回同一单无害，入单按渠道 + 外部单号幂等。 */
    List<ExternalOrder> pull(Instant since);

    /** 回传一条渠道订单行的交码 / 发货信息。 */
    WriteBackOutcome writeBack(ExternalOrderRef ref, WriteBack writeBack);

    /** 渠道某日（渠道结算口径的自然日）的结算单。 */
    List<ExternalStatementLine> statement(LocalDate day);
}
