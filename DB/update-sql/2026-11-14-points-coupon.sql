-- 积分兑券活动及成功流水。作者 Bulbasaur，创建日期 2026-11-14。
-- 只创建新业务表，不修改积分、优惠券及既有配置；重复执行保留已有记录。
-- 时间字段使用 Asia/Shanghai 业务时间；上线前备份并核对表结构，DDL 隐式提交，不能整份事务回滚。
-- 入口复用积分商品管理页签，不新增菜单、不修改角色授权。
-- 管理API位于 /manager/promotion/pointsGoods/coupons，沿用积分商品菜单现有通配权限。
-- 部署顺序：先执行本脚本，再发布并重启buyer-api、manager-api，重新构建管理端、PC买家端和移动端。
-- 首次配置：在优惠券管理创建平台活动券，再在积分商品管理的积分兑券页签创建并上架兑换活动。
CREATE TABLE IF NOT EXISTS li_points_coupon (
  id varchar(64) NOT NULL,
  create_by varchar(64) DEFAULT NULL,
  create_time datetime(3) DEFAULT NULL,
  update_by varchar(64) DEFAULT NULL,
  update_time datetime(3) DEFAULT NULL,
  delete_flag bit(1) NOT NULL DEFAULT b'0',
  name varchar(100) NOT NULL COMMENT '兑换活动名称',
  coupon_id varchar(64) NOT NULL COMMENT '平台活动券模板ID，创建后不可更换',
  points bigint NOT NULL COMMENT '每张券消耗的整数积分，大于0',
  total int NOT NULL COMMENT '累计可兑换张数，大于0',
  exchanged int NOT NULL DEFAULT 0 COMMENT '已成功兑换张数，只增不减',
  member_limit int NOT NULL COMMENT '每会员累计限兑次数，券使用或过期不恢复',
  enabled bit(1) NOT NULL DEFAULT b'0' COMMENT '0下架，1上架；仍受兑换时间约束',
  published bit(1) NOT NULL DEFAULT b'0' COMMENT '0草稿，1已发布；已下架后不可再编辑或重新上架',
  start_time datetime(3) NOT NULL COMMENT '兑换开始，Asia/Shanghai，含',
  end_time datetime(3) NOT NULL COMMENT '兑换结束，Asia/Shanghai，不含',
  PRIMARY KEY (id), KEY idx_points_coupon_list(enabled, start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分兑券活动';

CREATE TABLE IF NOT EXISTS li_points_coupon_record (
  id varchar(64) NOT NULL,
  create_by varchar(64) DEFAULT NULL,
  create_time datetime(3) DEFAULT NULL,
  update_by varchar(64) DEFAULT NULL,
  update_time datetime(3) DEFAULT NULL,
  delete_flag bit(1) NOT NULL DEFAULT b'0',
  activity_id varchar(64) NOT NULL COMMENT 'li_points_coupon.id',
  member_id varchar(64) NOT NULL COMMENT 'li_member.id',
  request_id varchar(64) NOT NULL COMMENT '会员内唯一幂等请求号',
  coupon_id varchar(64) NOT NULL COMMENT 'li_coupon.id',
  coupon_name varchar(255) NOT NULL COMMENT '成交时券名称快照',
  points bigint NOT NULL COMMENT '成交实际消耗积分快照',
  member_coupon_id varchar(64) NOT NULL COMMENT 'li_member_coupon.id，保存金额规则及有效期快照',
  PRIMARY KEY (id), UNIQUE KEY uk_points_coupon_request(member_id, request_id),
  KEY idx_points_coupon_member(activity_id, member_id), KEY idx_points_coupon_history(member_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分兑券成功流水，不主动退兑，过期不返积分';
-- 验证：SHOW CREATE TABLE li_points_coupon; SHOW CREATE TABLE li_points_coupon_record;
-- 验收：重复请求只一条流水；积分不足、库存不足或发券失败时，余额、计数和券均不变。
