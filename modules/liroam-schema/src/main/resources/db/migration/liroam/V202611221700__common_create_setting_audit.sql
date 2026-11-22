-- liroam 自有表口径（docs/domain-design.md §3，体检见 SchemaConventionsTest）：
-- 表前缀 lr_；主键 BIGINT UNSIGNED 自增；引用 Lilishop 的 ID 用 VARCHAR(64)、列名 li_ 前缀；
-- 交易金额 BIGINT 最小单位，币种由列名后缀定（_fen = 人民币分，_hkd_minor = 港仙，混币种表另带 currency）；
-- 资费单价 DECIMAL(18,6)、汇率 DECIMAL(18,10)；时间 DATETIME(3) 存 UTC（默认值用 UTC_TIMESTAMP，不用会话时区）；
-- 敏感字段 *_cipher VARBINARY + key_version，查询用 *_hash BINARY(32)。不改任何 li_ 表。

CREATE TABLE lr_setting (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    setting_key VARCHAR(128)    NOT NULL COMMENT '键与默认值在代码注册表 SettingKey，表只存覆盖值',
    scope_type  VARCHAR(16)     NOT NULL COMMENT 'GLOBAL/SUPPLIER/SKU/CHANNEL',
    scope_id    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'GLOBAL 为 0',
    value_json  JSON            NOT NULL,
    version     INT             NOT NULL DEFAULT 0,
    updated_by  VARCHAR(64)     NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at  DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_setting_scope (setting_key, scope_type, scope_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '运营配置覆盖值';

CREATE TABLE lr_audit_log (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor       VARCHAR(64)     NOT NULL,
    action      VARCHAR(64)     NOT NULL,
    target_type VARCHAR(32)     NOT NULL,
    target_id   VARCHAR(64)     NOT NULL,
    before_json JSON            NULL,
    after_json  JSON            NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_audit_target (target_type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '配置、调价确认、上架、人工处理留痕';
