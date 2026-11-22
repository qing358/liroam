package com.liroam.ops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.ops.OpsAlert;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.ops.health.OpsOverview;
import com.liroam.ops.health.OpsOverview.Overview;

/** 运维概览：库容量（整库与最大的表）与积压计数；整库占用达到容量阈值的 80% 出注意、达到 100% 出严重。 */
class OpsOverviewTest {

    private static OpsFixture f;

    @BeforeAll
    static void start() {
        f = OpsFixture.start("ops_overview");
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
    }

    @Test
    void reportsCapacityAndBacklogs() {
        f.h.jdbc.update("INSERT INTO lr_manual_task (kind, ref_type, ref_id, status, note) VALUES ('COMMAND_FAILED', 'X', '1', 'OPEN', 'n')");
        f.h.jdbc.update("INSERT INTO lr_manual_task (kind, ref_type, ref_id, status, note) VALUES ('COMMAND_FAILED', 'X', '2', 'DONE', 'n')");

        Overview o = f.bean(OpsOverview.class).overview();

        assertThat(o.databaseBytes()).isPositive();
        assertThat(o.capacityLimitBytes()).isEqualTo(20480L * 1024 * 1024);
        assertThat(o.tables()).isNotEmpty().hasSizeLessThanOrEqualTo(OpsOverview.TOP_TABLES)
                .isSortedAccordingTo((a, b) -> Long.compare(b.bytes(), a.bytes()));
        assertThat(o.backlogs()).extracting(OpsOverview.Backlog::key)
                .containsExactly("SUPPLIER_COMMANDS", "MANUAL_TASKS", "SHELL_DELIVERIES", "CHANNEL_WRITEBACKS", "RECON_DIFFS");
        assertThat(o.backlogs()).filteredOn(b -> b.key().equals("MANUAL_TASKS")).singleElement()
                .satisfies(b -> assertThat(b.count()).isEqualTo(1));
        assertThat(f.bean(OpsOverview.class).check(f.now())).isEmpty();
    }

    @Test
    void capacityAlertFollowsThreshold() {
        Settings settings = f.bean(Settings.class);
        OpsOverview overview = f.bean(OpsOverview.class);
        long bytes = overview.overview().databaseBytes();
        assertThat(bytes).as("测试库至少 1MB，才能用 1MB 阈值验证").isGreaterThan(1024L * 1024);

        settings.set(OpsOverview.CAPACITY_MB, SettingScope.GLOBAL, 1L, "op");
        assertThat(overview.check(f.now())).singleElement().satisfies(a -> {
            assertThat(a.code()).isEqualTo("DB_CAPACITY");
            assertThat(a.level()).isEqualTo(OpsAlert.Level.CRITICAL);
        });

        settings.set(OpsOverview.CAPACITY_MB, SettingScope.GLOBAL, 20480L, "op");
        assertThat(overview.check(f.now())).isEmpty();
    }

    @Test
    void capacityLevelBoundaries() {
        assertThat(OpsOverview.capacityLevel(79, 100)).isEmpty();
        assertThat(OpsOverview.capacityLevel(80, 100)).contains(OpsAlert.Level.WARN);
        assertThat(OpsOverview.capacityLevel(99, 100)).contains(OpsAlert.Level.WARN);
        assertThat(OpsOverview.capacityLevel(100, 100)).contains(OpsAlert.Level.CRITICAL);
        assertThat(OpsOverview.capacityLevel(5, 0)).as("容量设 0 即不检查").isEmpty();
    }
}
