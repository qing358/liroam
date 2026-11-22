package com.liroam.channel.testing;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.liroam.channel.MemberPhones;

/** 会员绑定手机号的假实现（壳侧在 shell-bridge）。 */
public final class FakeMemberPhones implements MemberPhones {

    private final Map<String, String> phones = new ConcurrentHashMap<>();

    public void bind(String memberId, String phone) {
        phones.put(memberId, phone);
    }

    public void reset() {
        phones.clear();
    }

    @Override
    public Optional<String> boundPhone(String memberId) {
        return Optional.ofNullable(phones.get(memberId));
    }
}
