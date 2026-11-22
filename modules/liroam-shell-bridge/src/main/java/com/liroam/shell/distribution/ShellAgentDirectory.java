package com.liroam.shell.distribution;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.commission.AgentDirectory;

/**
 * H9 只读：代理即壳分销员（{@code li_distribution}，审核通过且未删除）。一级 = 订单成交时壳写进 {@code li_order.distribution_id} 的分销员，
 * 二级 = 其 {@code parent_distribution_id}。只读表，不调壳分销服务，旅行卡不进壳分销奖励（H10）。
 */
public class ShellAgentDirectory implements AgentDirectory {

    private static final String SELECT = """
            SELECT CAST(id AS CHAR) AS agent_ref, member_id, member_name AS name, grade_id AS grade, parent_distribution_id
              FROM li_distribution
             WHERE distribution_status = 'PASS' AND (delete_flag IS NULL OR delete_flag = b'0')
            """;

    private record Row(String agentRef, String memberId, String name, String grade, String parentDistributionId) {

        Agent agent() {
            return new Agent(agentRef, memberId, name, grade);
        }
    }

    private final JdbcClient jdbc;

    public ShellAgentDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AgentChain> chainOfOrder(String orderSn) {
        Optional<String> distributionId = jdbc.sql("SELECT distribution_id FROM li_order WHERE sn = ? LIMIT 1").param(orderSn).query(String.class)
                .optional().filter(id -> !id.isBlank());
        return distributionId.flatMap(this::byRef).map(direct -> new AgentChain(direct.agent(),
                Optional.ofNullable(direct.parentDistributionId()).filter(p -> !p.isBlank()).flatMap(this::byRef).map(Row::agent).orElse(null)));
    }

    @Override
    public Optional<Agent> agentOfMember(String memberId) {
        return jdbc.sql(SELECT + " AND member_id = ? ORDER BY id LIMIT 1").param(memberId).query(Row.class).optional().map(Row::agent);
    }

    @Override
    public Map<String, Agent> agents(Collection<String> agentRefs) {
        List<String> refs = agentRefs.stream().filter(r -> r != null && r.matches("\\d{1,20}")).distinct().toList();
        if (refs.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql(SELECT + " AND id IN (:ids)").param("ids", refs.stream().map(Long::valueOf).toList()).query(Row.class).list().stream()
                .map(Row::agent).collect(Collectors.toMap(Agent::agentRef, Function.identity()));
    }

    private Optional<Row> byRef(String agentRef) {
        if (!agentRef.matches("\\d{1,20}")) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT + " AND id = ?").param(Long.valueOf(agentRef)).query(Row.class).optional();
    }
}
