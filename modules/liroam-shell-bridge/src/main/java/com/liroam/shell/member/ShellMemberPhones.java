package com.liroam.shell.member;

import java.util.Optional;

import com.liroam.channel.MemberPhones;

import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.service.MemberService;

/** 会员当前绑定的手机号 = 壳 {@code li_member.mobile}；渠道单认领时核对。 */
public class ShellMemberPhones implements MemberPhones {

    private final MemberService members;

    public ShellMemberPhones(MemberService members) {
        this.members = members;
    }

    @Override
    public Optional<String> boundPhone(String memberId) {
        if (memberId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(members.getById(memberId)).map(Member::getMobile).filter(m -> !m.isBlank());
    }
}
