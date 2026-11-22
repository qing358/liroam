-- 渠道（domain-design §4.5）。渠道单不建壳订单，直接进唯一履约入口；不另建库存与履约表。

CREATE TABLE lr_channel (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code                 VARCHAR(16)     NOT NULL,
    name                 VARCHAR(64)     NOT NULL,
    adapter_key          VARCHAR(64)     NOT NULL,
    enabled              TINYINT(1)      NOT NULL DEFAULT 0,
    pull_cron            VARCHAR(64)     NULL,
    buyer_can_view_usage TINYINT(1)      NOT NULL DEFAULT 0,
    commission_enabled   TINYINT(1)      NOT NULL DEFAULT 0,
    version              INT             NOT NULL DEFAULT 0,
    created_at           DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at           DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '外部卖场';

CREATE TABLE lr_channel_sku_mapping (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    channel_id      BIGINT UNSIGNED NOT NULL,
    external_sku_id VARCHAR(128)    NOT NULL,
    li_goods_sku_id VARCHAR(64)     NOT NULL,
    enabled         TINYINT(1)      NOT NULL DEFAULT 1,
    created_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_sku (channel_id, external_sku_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '渠道 SKU ↔ 本站 SKU';

CREATE TABLE lr_channel_order (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    channel_id           BIGINT UNSIGNED NOT NULL,
    external_order_id    VARCHAR(128)    NOT NULL,
    status               VARCHAR(16)     NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED/MAPPED/FULFILLING/DONE/MANUAL/CANCELLED',
    buyer_contact_cipher VARBINARY(512)  NULL,
    buyer_contact_hash   BINARY(32)      NULL,
    key_version          SMALLINT        NULL,
    raw_json             JSON            NULL,
    paid_fen             BIGINT          NOT NULL,
    ordered_at           DATETIME(3)     NULL,
    ordered_at_raw       VARCHAR(64)     NULL,
    version              INT             NOT NULL DEFAULT 0,
    created_at           DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at           DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_order (channel_id, external_order_id),
    KEY idx_channel_order_contact (buyer_contact_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '渠道订单';

CREATE TABLE lr_channel_order_line (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    channel_order_id BIGINT UNSIGNED NOT NULL,
    external_line_no VARCHAR(64)     NOT NULL,
    external_sku_id  VARCHAR(128)    NOT NULL,
    li_goods_sku_id  VARCHAR(64)     NULL COMMENT '映射不到为空并进人工队列',
    qty              INT             NOT NULL,
    paid_fen         BIGINT          NOT NULL,
    writeback_status VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    version          INT             NOT NULL DEFAULT 0,
    created_at       DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at       DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_order_line (channel_order_id, external_line_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '渠道订单行';
