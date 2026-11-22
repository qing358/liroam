package com.liroam.shell.security;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.UserEnums;

/**
 * worker 线程调壳服务时的操作人：壳的写服务经 {@code UserContext}/{@code OperationalJudgment} 取当前用户，
 * 后台线程没有登录态，这里临时放一个系统管理员身份，结束即恢复原上下文。
 */
public final class SystemOperator {

    public static final String USERNAME = "liroam-system";

    private SystemOperator() {
    }

    public static <T> T call(Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext system = SecurityContextHolder.createEmptyContext();
        var token = new UsernamePasswordAuthenticationToken(USERNAME, null, List.of());
        AuthUser user = new AuthUser(USERNAME, "0", "轻游系统", null, UserEnums.MANAGER);
        token.setDetails(user);
        system.setAuthentication(token);
        SecurityContextHolder.setContext(system);
        try {
            return action.get();
        } finally {
            if (previous.getAuthentication() == null) {
                SecurityContextHolder.clearContext();
            } else {
                SecurityContextHolder.setContext(previous);
            }
        }
    }
}
