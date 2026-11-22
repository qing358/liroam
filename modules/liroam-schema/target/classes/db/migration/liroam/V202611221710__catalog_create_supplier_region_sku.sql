-- 供应商与目录（domain-design §4.1）。使用区域引用一律 region_id / region_group_id 二选一（CHECK 约束）。

CREATE TABLE lr_supplier (
    id                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code                      VARCHAR(32)     NOT NULL COMMENT '领域内代号，如 SUP_A；业务代码不写厂商名',
    name                      VARCHAR(128)    NOT NULL,
    adapter_key               VARCHAR(64)     NOT NULL COMMENT '适配器 Bean 名',
    enabled                   TINYINT(1)      NOT NULL DEFAULT 1,
    listing_enabled           TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '该供应商 SKU 可否上架',
    capability_overrides_json JSON            NULL,
    rate_limit_per_min        INT             NULL COMMENT '覆盖适配器声明的令牌桶容量',
    version                   INT             NOT NULL DEFAULT 0,
    created_at                DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at                DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_supplier_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '上游卡商';

CREATE TABLE lr_card_product (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    supplier_id      BIGINT UNSIGNED NOT NULL,
    home_location    VARCHAR(8)      NOT NULL COMMENT '归属地 ISO 码，如 HK',
    card_type_code   VARCHAR(64)     NOT NULL COMMENT '上游资源码，如资费表资源列',
    name             VARCHAR(128)    NOT NULL,
    cap_data         TINYINT(1)      NOT NULL DEFAULT 1,
    cap_voice        TINYINT(1)      NOT NULL DEFAULT 0,
    cap_sms          TINYINT(1)      NOT NULL DEFAULT 0,
    has_number       TINYINT(1)      NOT NULL DEFAULT 0,
    kyc_policy       VARCHAR(16)     NOT NULL DEFAULT 'NONE' COMMENT 'NONE/CONDITIONAL/ALWAYS',
    allowed_forms    VARCHAR(64)     NOT NULL COMMENT '逗号分隔：ESIM,PHYSICAL,SOFT',
    constraints_json JSON            NULL,
    created_at       DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at       DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_product (supplier_id, card_type_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '卡产品：供应商 + 归属地 + 卡型';

CREATE TABLE lr_usage_region (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code       VARCHAR(16)     NOT NULL COMMENT 'ISO 3166-1 alpha-2，特殊地区自定码',
    name_zh    VARCHAR(64)     NOT NULL,
    continent  VARCHAR(32)     NULL COMMENT '资费表「区域」列，只作筛选',
    created_at DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_usage_region_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '使用区域（资费表国家/地区行）';

CREATE TABLE lr_region_group (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code       VARCHAR(32)     NOT NULL,
    name_zh    VARCHAR(128)    NOT NULL,
    created_at DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_region_group_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '多国包区域组';

CREATE TABLE lr_region_group_member (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    group_id   BIGINT UNSIGNED NOT NULL,
    region_id  BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_region_group_member (group_id, region_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

CREATE TABLE lr_card_product_region (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    card_product_id  BIGINT UNSIGNED NOT NULL,
    region_id        BIGINT UNSIGNED NOT NULL,
    active           TINYINT(1)      NOT NULL DEFAULT 1,
    kyc_required     TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '资费表注意事项与行备注并集导出',
    restriction_note TEXT            NULL COMMENT '资费表原文汇总，详情页使用区域说明的唯一来源',
    created_at       DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at       DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_product_region (card_product_id, region_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '可用区域';

CREATE TABLE lr_tariff_sheet (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    supplier_id    BIGINT UNSIGNED NOT NULL,
    currency       CHAR(3)         NOT NULL DEFAULT 'HKD',
    effective_from DATETIME(3)     NOT NULL,
    file_name      VARCHAR(255)    NOT NULL,
    file_sha256    CHAR(64)        NOT NULL,
    status         VARCHAR(16)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/SUPERSEDED',
    imported_by    VARCHAR(64)     NULL,
    version        INT             NOT NULL DEFAULT 0,
    created_at     DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at     DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_tariff_sheet_supplier (supplier_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '资费表版本';

CREATE TABLE lr_tariff_line (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    sheet_id      BIGINT UNSIGNED NOT NULL,
    resource_code VARCHAR(64)     NOT NULL,
    kind          VARCHAR(16)     NOT NULL COMMENT 'DATA/VOICE/SMS/CARD_FEE',
    continent_raw VARCHAR(64)     NULL,
    country_raw   VARCHAR(128)    NULL,
    region_id     BIGINT UNSIGNED NULL,
    carrier_raw   VARCHAR(128)    NULL,
    direction_raw VARCHAR(64)     NULL,
    unit          VARCHAR(8)      NOT NULL COMMENT 'GB/MIN/SMS/CARD',
    unit_price    DECIMAL(18, 6)  NOT NULL COMMENT '港币主单位/计量单位',
    remark_raw    TEXT            NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_tariff_line_sheet (sheet_id, resource_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '资费表原样导入行';

CREATE TABLE lr_region_tariff (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    sheet_id               BIGINT UNSIGNED NOT NULL,
    card_product_region_id BIGINT UNSIGNED NOT NULL,
    kind                   VARCHAR(16)     NOT NULL,
    direction              VARCHAR(16)     NOT NULL DEFAULT 'ANY',
    unit                   VARCHAR(8)      NOT NULL,
    unit_cost              DECIMAL(18, 6)  NOT NULL,
    carrier_rule           VARCHAR(16)     NOT NULL DEFAULT 'MAX' COMMENT '同国多运营商折一条：MAX/AVG/指定',
    source_line_ids_json   JSON            NULL,
    created_at             DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at             DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_region_tariff (sheet_id, card_product_region_id, kind, direction)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '区域资费（港币成本单价）';

CREATE TABLE lr_plan_template (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name             VARCHAR(128)    NOT NULL,
    data_quota_bytes BIGINT          NULL COMMENT 'NULL = 不限',
    voice_seconds    BIGINT          NULL,
    sms_count        INT             NULL,
    validity_days    INT             NOT NULL,
    start_rule       VARCHAR(16)     NOT NULL DEFAULT 'ON_ACTIVATION' COMMENT 'ON_ACTIVATION/ON_PURCHASE',
    created_at       DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at       DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '套餐模板：N 天 M GB';

CREATE TABLE lr_supplier_offering (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    supplier_id           BIGINT UNSIGNED NOT NULL,
    card_product_id       BIGINT UNSIGNED NOT NULL,
    region_id             BIGINT UNSIGNED NULL,
    region_group_id       BIGINT UNSIGNED NULL,
    plan_template_id      BIGINT UNSIGNED NOT NULL,
    kind                  VARCHAR(16)     NOT NULL COMMENT 'BASE/TOP_UP',
    upstream_package_code VARCHAR(128)    NOT NULL,
    upstream_payload_json JSON            NULL,
    enabled               TINYINT(1)      NOT NULL DEFAULT 1,
    created_at            DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at            DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_offering_product (supplier_id, card_product_id),
    CONSTRAINT ck_offering_region CHECK ((region_id IS NULL) <> (region_group_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '套餐映射：领域套餐 → 上游套餐码';

CREATE TABLE lr_sku_binding (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    li_goods_sku_id  VARCHAR(64)     NOT NULL,
    li_goods_id      VARCHAR(64)     NOT NULL,
    sku_kind         VARCHAR(16)     NOT NULL COMMENT 'NEW_CARD/TOP_UP',
    supplier_id      BIGINT UNSIGNED NOT NULL,
    card_product_id  BIGINT UNSIGNED NOT NULL,
    region_id        BIGINT UNSIGNED NULL,
    region_group_id  BIGINT UNSIGNED NULL,
    form             VARCHAR(16)     NOT NULL COMMENT 'ESIM/PHYSICAL/SOFT',
    plan_template_id BIGINT UNSIGNED NOT NULL,
    offering_id      BIGINT UNSIGNED NOT NULL,
    kyc_gate_mode    VARCHAR(16)     NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PROMPT/BLOCK_DELIVERY',
    numbered_gate    TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否含号码',
    listing_state    VARCHAR(16)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/LISTED/DELISTED',
    locked_at        DATETIME(3)     NULL COMMENT '首次 LISTED 时由触发器写入，之后供应商/卡产品/使用区域不可改',
    version          INT             NOT NULL DEFAULT 0,
    created_at       DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at       DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_binding_sku (li_goods_sku_id),
    KEY idx_sku_binding_supplier (supplier_id),
    CONSTRAINT ck_sku_binding_region CHECK ((region_id IS NULL) <> (region_group_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '旅行卡 SKU：li_goods_sku 的 1:1 扩展';

DELIMITER $$

CREATE TRIGGER lr_sku_binding_lock_bi BEFORE INSERT ON lr_sku_binding FOR EACH ROW
BEGIN
    IF NEW.listing_state = 'LISTED' AND NEW.locked_at IS NULL THEN
        SET NEW.locked_at = UTC_TIMESTAMP(3);
    END IF;
END$$

CREATE TRIGGER lr_sku_binding_lock_bu BEFORE UPDATE ON lr_sku_binding FOR EACH ROW
BEGIN
    IF OLD.locked_at IS NOT NULL AND (
            NOT (NEW.supplier_id <=> OLD.supplier_id)
            OR NOT (NEW.card_product_id <=> OLD.card_product_id)
            OR NOT (NEW.region_id <=> OLD.region_id)
            OR NOT (NEW.region_group_id <=> OLD.region_group_id)
            OR NOT (NEW.li_goods_sku_id <=> OLD.li_goods_sku_id)
            OR NOT (NEW.locked_at <=> OLD.locked_at)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LR_SKU_LOCKED: SKU 已上架锁定，供应商/卡产品/使用区域不可改，请新开 SKU';
    END IF;
    IF NEW.listing_state = 'LISTED' AND OLD.locked_at IS NULL THEN
        SET NEW.locked_at = UTC_TIMESTAMP(3);
    END IF;
END$$

DELIMITER ;
