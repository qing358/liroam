package cn.lili.controller.manager.promotion;

import cn.lili.common.enums.ResultUtil;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.vo.PageVO;
import cn.lili.common.vo.ResultMessage;
import cn.lili.modules.promotion.entity.dos.PointsCoupon;
import cn.lili.modules.promotion.service.PointsCouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端积分兑券配置、上下架及成功流水入口，接口放在积分商品路径下复用既有菜单权限。
 *
 * @author Bulbasaur
 * @since 2026-11-14
 */
@RestController
@Tag(name = "管理端,积分兑券")
@RequestMapping("/manager/promotion/pointsGoods/coupons")
public class PointsCouponManagerController {
    @Autowired
    private PointsCouponService service;

    @Operation(summary = "分页查询积分兑券活动", description = "查询管理端活动配置，包含上下架状态和库存")
    @GetMapping
    public ResultMessage<?> activities(PageVO page) { return ResultUtil.data(service.activities(page, false)); }

    @Operation(summary = "保存积分兑券活动", description = "创建或更新活动配置，新增活动默认下架")
    @PostMapping
    public ResultMessage<?> configure(@Parameter(description = "积分兑券活动配置", required = true) @RequestBody PointsCoupon input) { return ResultUtil.data(service.configure(input)); }

    @Operation(summary = "上下架积分兑券活动", description = "更新活动状态，保留已发优惠券和兑换记录")
    @PutMapping("/{id}/status")
    public ResultMessage<?> status(@Parameter(description = "积分兑券活动ID", required = true) @PathVariable String id,
            @Parameter(description = "true上架、false下架", required = true) @RequestParam boolean enabled) {
        service.changeStatus(id, enabled);
        return ResultUtil.success();
    }

    @Operation(summary = "分页查询积分兑券记录", description = "查询全部会员的成功兑换记录")
    @GetMapping("/records")
    public ResultMessage<?> records(PageVO page) { return ResultUtil.data(service.records(page, null)); }

    @ExceptionHandler(ServiceException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResultMessage<?> handleBusinessError(ServiceException e) {
        return ResultUtil.error(e.getResultCode().code(), e.getMsg());
    }
}
