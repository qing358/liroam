package com.liroam.common.web;

/**
 * 当前请求的操作人。实现在 {@code liroam-shell-bridge}（读壳登录态），自有模块的接口只依赖这个端口。
 */
public interface CurrentActor {

    /** 当前登录买家（壳 {@code li_member.id}）；未登录或不是买家抛 {@link UserFacingException}。 */
    String requireMemberId();

    /** 审计用的操作人标识（如 {@code manager:admin}）；未登录抛 {@link UserFacingException}。 */
    String requireOperator();
}
