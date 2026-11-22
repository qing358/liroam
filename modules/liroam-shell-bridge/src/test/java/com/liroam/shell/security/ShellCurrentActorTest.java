package com.liroam.shell.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.liroam.common.web.UserFacingException;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.UserEnums;

class ShellCurrentActorTest {

    private final ShellCurrentActor actor = new ShellCurrentActor();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String username, String id, UserEnums role) {
        var token = new UsernamePasswordAuthenticationToken(username, null, java.util.List.of());
        token.setDetails(new AuthUser(username, id, username, null, role));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    void memberIsTheShellMemberId() {
        login("liroam_p0_buyer", "2124425379497119744", UserEnums.MEMBER);

        assertThat(actor.requireMemberId()).isEqualTo("2124425379497119744");
        assertThat(actor.requireOperator()).isEqualTo("member:liroam_p0_buyer");
    }

    @Test
    void managerIsAnOperatorButNotABuyer() {
        login("admin", "1", UserEnums.MANAGER);

        assertThat(actor.requireOperator()).isEqualTo("manager:admin");
        assertThatThrownBy(actor::requireMemberId).isInstanceOf(UserFacingException.class);
    }

    @Test
    void anonymousIsRejected() {
        assertThatThrownBy(actor::requireMemberId).isInstanceOf(UserFacingException.class).hasMessageContaining("登录");
        assertThatThrownBy(actor::requireOperator).isInstanceOf(UserFacingException.class);
    }
}
