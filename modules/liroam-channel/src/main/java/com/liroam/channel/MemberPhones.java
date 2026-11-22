package com.liroam.channel;

import java.util.Optional;

/** 端口：会员（壳 {@code li_member.id}）当前绑定的手机号；由 {@code liroam-shell-bridge} 实现。 */
@FunctionalInterface
public interface MemberPhones {

    Optional<String> boundPhone(String memberId);
}
