package com.liroam.common.web;

/**
 * 自有接口的返回包，字段与壳 {@code ResultMessage} 一致（success/message/code/timestamp/result），三端前端拦截器无需区分。
 */
public record ApiResult<T>(boolean success, String message, int code, long timestamp, T result) {

    public static final int OK = 200;
    public static final int USER_ERROR = 400;

    public static <T> ApiResult<T> ok(T result) {
        return new ApiResult<>(true, "成功", OK, System.currentTimeMillis(), result);
    }

    public static <T> ApiResult<T> fail(String message) {
        return new ApiResult<>(false, message, USER_ERROR, System.currentTimeMillis(), null);
    }
}
