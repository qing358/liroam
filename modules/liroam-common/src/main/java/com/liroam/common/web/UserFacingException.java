package com.liroam.common.web;

/** 业务校验失败，消息直接展示给用户（中文、说清怎么办）；自有接口统一转成 {@link ApiResult#fail}。 */
public class UserFacingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UserFacingException(String message) {
        super(message);
    }
}
