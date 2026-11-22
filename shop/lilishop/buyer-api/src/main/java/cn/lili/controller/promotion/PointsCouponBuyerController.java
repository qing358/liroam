package cn.lili.controller.promotion;

import cn.lili.common.enums.ResultUtil;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.vo.PageVO;
import cn.lili.common.vo.ResultMessage;
import cn.lili.modules.promotion.service.PointsCouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 买家积分兑券入口，身份只取登录上下文，客户端不能指定其他会员。
 *
 * @author Bulbasaur
 * @since 2026-11-14
 */
@RestController
@Tag(name = "买家端,积分兑券")
@RequestMapping("/buyer/promotion/pointsCoupon")
public class PointsCouponBuyerController {
    @Autowired
    private PointsCouponService service;

    @Operation(summary = "分页查询积分兑券活动", description = "查询有效上架活动，包含所需积分和优惠券信息")
    @GetMapping
    public ResultMessage<?> activities(PageVO page) { return ResultUtil.data(service.activities(page, true)); }

    @Operation(summary = "积分兑换优惠券", description = "每次兑换一张，扣积分、发券及记录共同提交；重试必须复用请求号")
    @PostMapping("/{id}/exchange")
    public ResultMessage<?> exchange(@Parameter(description = "积分兑券活动ID", required = true) @PathVariable String id,
            @Parameter(description = "会员内唯一请求号，重试必须复用", required = true) @RequestParam String requestId,
            @Parameter(description = "用户确认的兑换积分数，服务端再次核对活动价格", required = true) @RequestParam Long points) {
        return ResultUtil.data(service.exchange(id, memberId(), requestId, points));
    }

    @Operation(summary = "分页查询本人兑券记录", description = "仅查询当前登录会员的成功兑换记录")
    @GetMapping("/records")
    public ResultMessage<?> records(PageVO page) { return ResultUtil.data(service.records(page, memberId())); }

    private String memberId() {
        var user = UserContext.getCurrentUser();
        if (user == null || user.getRole() != cn.lili.common.security.enums.UserEnums.MEMBER)
            throw new ServiceException(ResultCode.USER_AUTHORITY_ERROR);
        return user.getId();
    }

    @ExceptionHandler(ServiceException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResultMessage<?> handleBusinessError(ServiceException e) {
        return ResultUtil.error(e.getResultCode().code(), e.getMsg());
    }
}
