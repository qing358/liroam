package com.liroam.common.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 集成测试用 MySQL：只连本项目专用中间件。
 * <p>
 * 主机固定 127.0.0.1，端口只取仓内唯一登记 {@code deploy/ports.env} 的 {@code LIROAM_MYSQL_PORT}，不接受任何覆盖；
 * 口令取环境变量 {@code LIROAM_MYSQL_ROOT_PASSWORD}（CI 注入），没有则读仓根 {@code .env}。
 * 每个模块用自己的库 {@code liroam_it_<模块>}，每次重建，不碰 {@code lilishop} 库。
 * JDBC 参数与壳一致（{@code serverTimezone=Asia/Shanghai}），时间列口径在这个条件下验证。
 */
public final class ItDatabase {

    /** 本机其它项目占用的端口，即使 ports.env 被误改也拒绝。 */
    static final Set<Integer> FORBIDDEN_PORTS = Set.of(3306, 6379);

    private ItDatabase() {
    }

    private static final String PARAMS = "?useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    public static DataSource fresh(String module) {
        String schema = schema(module);
        DriverManagerDataSource admin = connect("");
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + schema);
            s.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        } catch (SQLException e) {
            throw new IllegalStateException("无法在 " + admin.getUrl() + " 重建测试库 " + schema + "：" + e.getMessage(), e);
        }
        return connect(schema);
    }

    /** 再开一个连接源指向已建好的测试库（模拟第二个应用实例），不重建。 */
    public static DataSource reopen(String module) {
        return connect(schema(module));
    }

    /** 多个 worktree 同时跑测试时用不同前缀，避免同名测试类互删库；必须以 {@code liroam_it_} 开头。 */
    static final String PREFIX_ENV = "LIROAM_IT_DB_PREFIX";

    private static String schema(String module) {
        if (!module.matches("[a-z_]+")) {
            throw new IllegalArgumentException("模块名只允许小写字母与下划线：" + module);
        }
        return prefix(System.getenv(PREFIX_ENV)) + module;
    }

    static String prefix(String raw) {
        if (raw == null || raw.isBlank()) {
            return "liroam_it_";
        }
        if (!raw.matches("liroam_it_[a-z0-9_]*")) {
            throw new IllegalArgumentException(PREFIX_ENV + " 必须形如 liroam_it_xxx_：" + raw);
        }
        return raw;
    }

    private static DriverManagerDataSource connect(String schema) {
        Path root = repoRoot();
        int port = checkedPort(parseEnv(root.resolve("deploy/ports.env")).get("LIROAM_MYSQL_PORT"));
        String password = System.getenv("LIROAM_MYSQL_ROOT_PASSWORD");
        if (password == null) {
            Path secrets = root.resolve(".env");
            if (!Files.exists(secrets)) {
                throw new IllegalStateException("集成测试需要本项目 MySQL 口令：设置环境变量 LIROAM_MYSQL_ROOT_PASSWORD，或先在主检出执行 node scripts/dev.mjs up 生成 .env");
            }
            password = parseEnv(secrets).get("LIROAM_MYSQL_ROOT_PASSWORD");
        }
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port + "/" + schema + PARAMS, "root", password);
    }

    static int checkedPort(String raw) {
        if (raw == null) {
            throw new IllegalStateException("deploy/ports.env 未登记 LIROAM_MYSQL_PORT");
        }
        int port = Integer.parseInt(raw.trim());
        if (FORBIDDEN_PORTS.contains(port)) {
            throw new IllegalStateException("拒绝连接端口 " + port + "：属于本机其它项目");
        }
        return port;
    }

    static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("deploy/ports.env"))) {
                return p;
            }
        }
        throw new IllegalStateException("从 " + dir + " 向上找不到 deploy/ports.env");
    }

    static Map<String, String> parseEnv(Path file) {
        Map<String, String> result = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.indexOf('=') <= 0) {
                    continue;
                }
                String value = t.substring(t.indexOf('=') + 1).trim();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(t.substring(0, t.indexOf('=')).trim(), value);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }
}
