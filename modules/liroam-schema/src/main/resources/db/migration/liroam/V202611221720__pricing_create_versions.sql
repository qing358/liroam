-- 定价（domain-design §4.2）。三类价格版本只增不改；订单行快照引用版本 ID。

CREATE TABLE lr_fx_rate_version (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    base_currency        CHAR(3)         NOT NULL DEFAULT 'HKD',
    quote_currency       CHAR(3)         NOT NULL DEFAULT 'CNY',
    rate                 DECIMAL(18, 10) NOT NULL,
    source_key           VARCHAR(64)     NOT NULL,
    source_published_at  DATETIME(3)     NULL,
    source_published_raw VARCHAR(64)     NULL,
    fetched_at           DATETIME(3)     NOT NULL,
    status               VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SUPERSEDED/REJECTED',
    version              INT             NOT NULL DEFAULT 0,
    created_at           DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at           DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_fx_pair_status (base_currency, quote_currency, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '汇率版本（每次同步成功一行）';

CREATE TABLE lr_pricing_rule (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type      VARCHAR(16)     NOT NULL COMMENT 'GLOBAL/SUPPLIER/CARD_PRODUCT/SKU',
    scope_id        BIGINT UNSIGNED NOT NULL DEFAULT 0,
    markup_mode     VARCHAR(16)     NOT NULL COMMENT 'MARGIN_RATE/FIXED_ADD',
    markup_rate     DECIMAL(9, 6)   NULL COMMENT 'MARGIN_RATE 用',
    markup_add_fen  BIGINT          NULL COMMENT 'FIXED_ADD 用',
    rounding        VARCHAR(32)     NULL COMMENT '如 尾数 .9',
    min_margin_rate DECIMAL(9, 6)   NULL,
    version         INT             NOT NULL DEFAULT 0,
    effective_from  DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    created_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_pricing_rule_scope (scope_type, scope_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '自动重算用的加价规则';

CREATE TABLE lr_sku_cost_version (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    sku_binding_id     BIGINT UNSIGNED NOT NULL,
    tariff_sheet_id    BIGINT UNSIGNED NOT NULL,
    fx_rate_version_id BIGINT UNSIGNED NOT NULL,
    cost_hkd_minor     BIGINT          NOT NULL,
    cost_cny_fen       BIGINT          NOT NULL,
    breakdown_json     JSON            NULL COMMENT '流量×单价 + 卡费 + 号码月租…',
    created_at         DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_cost_version_sku (sku_binding_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'SKU 成本版本 = 资费 × 汇率';

CREATE TABLE lr_sku_price_version (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    sku_binding_id  BIGINT UNSIGNED NOT NULL,
    price_cny_fen   BIGINT          NOT NULL,
    cost_version_id BIGINT UNSIGNED NOT NULL,
    origin          VARCHAR(16)     NOT NULL COMMENT 'MANUAL/AUTO/SUGGESTION',
    suggestion_id   BIGINT UNSIGNED NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SUPERSEDED',
    activated_at    DATETIME(3)     NULL,
    activated_by    VARCHAR(64)     NULL,
    version         INT             NOT NULL DEFAULT 0,
    created_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_price_version_sku (sku_binding_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'SKU 售价版本（人民币），激活同事务写回 li_goods_sku.price';

CREATE TABLE lr_reprice_suggestion (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    sku_binding_id      BIGINT UNSIGNED NOT NULL,
    trigger_kind        VARCHAR(16)     NOT NULL COMMENT 'FX/TARIFF',
    old_cost_version_id BIGINT UNSIGNED NULL,
    new_cost_version_id BIGINT UNSIGNED NOT NULL,
    current_price_fen   BIGINT          NOT NULL,
    suggested_price_fen BIGINT          NOT NULL,
    margin_before       DECIMAL(9, 6)   NULL,
    margin_after        DECIMAL(9, 6)   NULL,
    status              VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/REJECTED/SUPERSEDED',
    decided_by          VARCHAR(64)     NULL,
    decided_at          DATETIME(3)     NULL,
    version             INT             NOT NULL DEFAULT 0,
    created_at          DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at          DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_reprice_sku_status (sku_binding_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '调价建议（默认提醒 + 人工确认）';

CREATE TABLE lr_order_line_snapshot (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_type         VARCHAR(16)     NOT NULL COMMENT 'SHOP/CHANNEL/TOPUP',
    source_line_ref     VARCHAR(128)    NOT NULL,
    li_order_sn         VARCHAR(64)     NULL,
    sku_binding_id      BIGINT UNSIGNED NOT NULL,
    supplier_id         BIGINT UNSIGNED NOT NULL,
    card_product_id     BIGINT UNSIGNED NOT NULL,
    region_id           BIGINT UNSIGNED NULL,
    region_group_id     BIGINT UNSIGNED NULL,
    usage_region_text   TEXT            NOT NULL COMMENT '下单时详情页使用区域说明全文',
    tariff_sheet_id     BIGINT UNSIGNED NULL,
    fx_rate_version_id  BIGINT UNSIGNED NULL,
    cost_version_id     BIGINT UNSIGNED NULL,
    price_version_id    BIGINT UNSIGNED NULL,
    unit_price_fen      BIGINT          NOT NULL,
    paid_fen            BIGINT          NOT NULL,
    cost_cny_fen        BIGINT          NULL,
    qty                 INT             NOT NULL,
    topup_card_asset_id BIGINT UNSIGNED NULL,
    created_at          DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_snapshot_source (source_type, source_line_ref),
    KEY idx_snapshot_order (li_order_sn),
    CONSTRAINT ck_snapshot_region CHECK ((region_id IS NULL) <> (region_group_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '订单行快照：毛利、佣金、对账都按它算';
