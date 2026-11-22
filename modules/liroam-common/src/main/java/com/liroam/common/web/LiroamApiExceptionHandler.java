package com.liroam.common.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 只作用于自有控制器（{@code com.liroam}），先于壳的全局异常处理；HTTP 状态保持 200，与壳返回包一致。 */
@RestControllerAdvice(basePackages = "com.liroam")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LiroamApiExceptionHandler {

    @ExceptionHandler(UserFacingException.class)
    public ApiResult<Void> userFacing(UserFacingException e) {
        return ApiResult.fail(e.getMessage());
    }
}
