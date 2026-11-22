package com.liroam.schema.testing;

import javax.sql.DataSource;

import com.liroam.common.testing.ItDatabase;
import com.liroam.schema.LiroamMigrations;

/** 重建本模块测试库并执行全部自有迁移。 */
public final class ItSchema {

    private ItSchema() {
    }

    public static DataSource migrated(String module) {
        DataSource ds = ItDatabase.fresh(module);
        LiroamMigrations.migrate(ds);
        return ds;
    }
}
