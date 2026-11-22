package com.liroam.shell.security;

import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.UserFacingException;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.security.enums.UserEnums;

/** {@link CurrentActor} 的壳实现：读壳登录态（Spring Security 上下文或请求头 token）。 */
public class ShellCurrentActor implements CurrentActor {

    @Override
    public String requireMemberId() {
        AuthUser user = UserContext.getCurrentUser();
        if (user == null || user.getRole() != UserEnums.MEMBER) {
            throw new UserFacingException("请先登录买家账号");
        }
        return user.getId();
    }

    @Override
    public String requireOperator() {
        AuthUser user = UserContext.getCurrentUser();
        if (user == null || user.getRole() == null) {
            throw new UserFacingException("请先登录");
        }
        return user.getRole().name().toLowerCase() + ":" + user.getUsername();
    }
}
