-- 本机演示分销：只由 `node scripts/dev.mjs demo` 执行，幂等；不得进入任何正式环境迁移。
-- 演示买家 liroam_p0_buyer 设为已审核分销员、邀请人取库里另一个已审核分销员（没有则不设，只有一级），并把买家锁客到自己名下：
-- 自己下单 = 一级佣金归自己（「我的佣金」可见）、二级归邀请人。正式环境由分享 / 扫码锁客建立关系，不走这里。
-- 佣金策略不在这里写：demo 通过后台接口建（与运营操作同一路径）。

SET NAMES utf8mb4 COLLATE utf8mb4_general_ci;

SET @buyer := (SELECT id FROM li_member WHERE username = 'liroam_p0_buyer' LIMIT 1);
SET @agent := (SELECT id FROM li_distribution WHERE member_id = @buyer LIMIT 1);
SET @inviter := (SELECT id FROM li_distribution WHERE distribution_status = 'PASS' AND id <> COALESCE(@agent, '') ORDER BY create_time LIMIT 1);

UPDATE li_distribution SET distribution_status = 'PASS', parent_distribution_id = @inviter, update_time = NOW()
 WHERE id = @agent;

INSERT INTO li_distribution_customer_relation (id, member_id, distribution_id, bind_source, relation_status, bind_time, permanent_at, create_by, create_time)
SELECT CONCAT('91', @buyer), @buyer, @agent, 'ADMIN', 'PERMANENT', NOW(), NOW(), 'liroam-demo', NOW()
  FROM DUAL WHERE @agent IS NOT NULL
ON DUPLICATE KEY UPDATE distribution_id = VALUES(distribution_id), relation_status = 'PERMANENT', expire_at = NULL, delete_flag = 0,
                        update_by = 'liroam-demo', update_time = NOW();

SELECT @buyer AS member_id, @agent AS agent, @inviter AS inviter;
