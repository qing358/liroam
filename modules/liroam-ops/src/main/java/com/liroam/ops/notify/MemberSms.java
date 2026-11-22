package com.liroam.ops.notify;

/**
 * 给会员发短信的端口（可换家的外部能力）：实现方按会员 id 取手机号并调短信服务商。生产实现待短信服务商与模板确定后接入；
 * 没有实现时到期提醒只发站内通知。
 *
 * @return 服务商受理成功
 */
public interface MemberSms {

    boolean send(String memberId, String text);
}
