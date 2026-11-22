package com.liroam.shell.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.service.MemberService;

/** 渠道认领用的「当前账号绑定手机号」取壳会员 {@code li_member.mobile}。 */
class ShellMemberPhonesTest {

    private final MemberService members = mock(MemberService.class);
    private final ShellMemberPhones phones = new ShellMemberPhones(members);

    @Test
    void boundPhoneIsTheShellMembersMobile() {
        Member m = new Member();
        m.setMobile("13800138000");
        when(members.getById("m1")).thenReturn(m);
        Member blank = new Member();
        blank.setMobile(" ");
        when(members.getById("m2")).thenReturn(blank);

        assertThat(phones.boundPhone("m1")).contains("13800138000");
        assertThat(phones.boundPhone("m2")).isEmpty();
        assertThat(phones.boundPhone("nobody")).isEmpty();
        assertThat(phones.boundPhone(null)).isEmpty();
    }
}
