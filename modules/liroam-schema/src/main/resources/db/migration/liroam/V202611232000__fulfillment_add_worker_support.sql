-- 履约 worker 补充（domain-design §6.3 限流、§7.5 交码超时、§5 H5 发货回写）。

-- 每供应商一个令牌桶，多实例共享（Bucket4j MySQL 后端，SELECT … FOR UPDATE；键列 bucket_key，状态列 state）。
CREATE TABLE lr_supplier_rate_bucket (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    bucket_key VARCHAR(128)    NOT NULL COMMENT 'supplier:{供应商 id}:{每分钟容量}；改容量即换新桶',
    state      BLOB            NULL COMMENT 'Bucket4j 序列化状态',
    created_at DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rate_bucket_key (bucket_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '供应商令牌桶（写优先，轮询不吃预留）';

ALTER TABLE lr_card_asset
    ADD COLUMN provisioned_at DATETIME(3) NULL COMMENT '进入 PROVISIONED 的时间，交码超时从这里算' AFTER activated_at;

-- 整单全部卡交码后待回写壳订单（H5）；回写失败留在 PENDING 由 worker 重扫。
CREATE TABLE lr_shell_delivery (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    li_order_sn     VARCHAR(64)     NOT NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE',
    attempts        INT             NOT NULL DEFAULT 0,
    last_error      VARCHAR(512)    NULL,
    next_attempt_at DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    locked_until    DATETIME(3)     NULL,
    version         INT             NOT NULL DEFAULT 0,
    created_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_shell_delivery_order (li_order_sn),
    KEY idx_shell_delivery_due (status, next_attempt_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '壳订单发货回写待办（H5，幂等）';
