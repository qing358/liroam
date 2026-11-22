-- 实体卡交码（kind = SHIPMENT）回写壳发货要的物流公司 id（li_logistics.id）
ALTER TABLE lr_card_credential
    ADD COLUMN logistics_ref VARCHAR(64) NULL COMMENT '壳物流公司 id（li_logistics.id），仅 SHIPMENT' AFTER channel;
