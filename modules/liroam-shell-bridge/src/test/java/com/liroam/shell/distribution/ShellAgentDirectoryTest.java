package com.liroam.shell.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.commission.AgentDirectory.Agent;
import com.liroam.schema.testing.ItSchema;

/** H9 只读：代理 = 审核通过（PASS）且未删除的壳分销员；订单挂的分销员为一级，其上级（也须 PASS）为二级。 */
class ShellAgentDirectoryTest {

    private static JdbcTemplate jdbc;
    private static ShellAgentDirectory agents;

    @BeforeAll
    static void setUp() {
        DataSource ds = ItSchema.migrated("shell_agents");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS li_distribution (id BIGINT PRIMARY KEY, delete_flag BIT(1), distribution_status VARCHAR(255), "
                + "grade_id VARCHAR(64), parent_distribution_id VARCHAR(64), member_id VARCHAR(255), member_name VARCHAR(255)) "
                + "DEFAULT CHARSET = utf8mb3 COLLATE = utf8mb3_general_ci");
        jdbc.execute("CREATE TABLE IF NOT EXISTS li_order (id BIGINT PRIMARY KEY, sn VARCHAR(255), distribution_id VARCHAR(255)) "
                + "DEFAULT CHARSET = utf8mb3 COLLATE = utf8mb3_general_ci");
        agents = new ShellAgentDirectory(JdbcClient.create(ds));
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM li_distribution");
        jdbc.update("DELETE FROM li_order");
        jdbc.update("INSERT INTO li_distribution VALUES (1, b'0', 'PASS', '2', NULL, 'm1', '上级'), (2, b'0', 'PASS', '1', '1', 'm2', '直属'), "
                + "(3, b'0', 'APPLY', NULL, NULL, 'm3', '申请中'), (4, b'0', 'PASS', '1', '3', 'm4', '上级未通过'), (5, b'1', 'PASS', '1', NULL, 'm5', '已删')");
        jdbc.update("INSERT INTO li_order VALUES (10, 'O-2', '2'), (11, 'O-4', '4'), (12, 'O-3', '3'), (13, 'O-NONE', NULL), (14, 'O-5', '5')");
    }

    @Test
    void chainOfOrderHasDirectAndPassedInviter() {
        assertThat(agents.chainOfOrder("O-2")).hasValueSatisfying(c -> {
            assertThat(c.direct()).isEqualTo(new Agent("2", "m2", "直属", "1"));
            assertThat(c.inviter()).isEqualTo(new Agent("1", "m1", "上级", "2"));
        });
        assertThat(agents.chainOfOrder("O-4")).hasValueSatisfying(c -> assertThat(c.inviter()).isNull());
    }

    @Test
    void unapprovedDeletedOrMissingDistributorIsNoAgent() {
        assertThat(agents.chainOfOrder("O-3")).isEmpty();
        assertThat(agents.chainOfOrder("O-5")).isEmpty();
        assertThat(agents.chainOfOrder("O-NONE")).isEmpty();
        assertThat(agents.chainOfOrder("O-MISSING")).isEmpty();
        assertThat(agents.agentOfMember("m3")).isEmpty();
        assertThat(agents.agentOfMember("m5")).isEmpty();
    }

    @Test
    void lookupByMemberAndByRefs() {
        assertThat(agents.agentOfMember("m2")).hasValue(new Agent("2", "m2", "直属", "1"));
        assertThat(agents.agents(List.of("1", "2", "3"))).containsOnlyKeys("1", "2");
        assertThat(agents.agents(List.of())).isEmpty();
    }
}
