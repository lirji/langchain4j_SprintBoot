package com.lrj.langchain4j.rag.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MySQL 边表持久化的 {@link GraphStore}（{@code app.rag.graph.store=jdbc}）。重启不丢，
 * 走 {@code DriverManager} + 自建表，跟 {@code DorisEmbeddingStore} 同款脚手架级 JDBC 写法
 * （刻意不引连接池——规模上来再换 Hikari + Stream load）。
 *
 * <p><strong>设计取舍记录</strong>：G3 持久化原设计点名 Neo4j，落地改成 MySQL 边表——零新依赖
 * （{@code mysql-connector-j} 已在；本机 MySQL 可达）、跟 workflow/Doris 复用同一基建、可验证，
 * 跟项目当初「Doris 自实现 JDBC 而非等官方模块」一个判断。Neo4j 分支仍可后补。
 *
 * <p>遍历 = 每跳一条 {@code WHERE tenant=? AND (subject_norm IN(..) OR object_norm IN(..))}，
 * 用归一列（trim+lower）走索引。租户隔离写进每条 SQL 谓词，跟内存实现语义一致。
 * 表名只允许 {@code [A-Za-z0-9_]}，防注入（值都走 PreparedStatement 占位）。
 */
public class JdbcGraphStore implements GraphStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcGraphStore.class);

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String table;

    public JdbcGraphStore(String jdbcUrl, String user, String password, String table, boolean createTable) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password == null ? "" : password;
        if (table == null || !table.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("invalid graph table name: " + table);
        }
        this.table = table;
        if (createTable) createTableIfMissing();
        log.info("JdbcGraphStore ready: url={} table={}", jdbcUrl, table);
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    private void createTableIfMissing() {
        String ddl = """
                CREATE TABLE IF NOT EXISTS %s (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  subject VARCHAR(512) NOT NULL,
                  relation VARCHAR(256) NOT NULL,
                  object VARCHAR(512) NOT NULL,
                  subject_norm VARCHAR(512) NOT NULL,
                  object_norm VARCHAR(512) NOT NULL,
                  source_id VARCHAR(512),
                  tenant_id VARCHAR(128),
                  category VARCHAR(128),
                  KEY idx_subj (tenant_id, subject_norm),
                  KEY idx_obj (tenant_id, object_norm),
                  KEY idx_src (tenant_id, source_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(table);
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute(ddl);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create graph table " + table, e);
        }
    }

    @Override
    public void add(List<Triple> triples) {
        if (triples == null || triples.isEmpty()) return;
        String sql = "INSERT INTO " + table
                + " (subject, relation, object, subject_norm, object_norm, source_id, tenant_id, category)"
                + " VALUES (?,?,?,?,?,?,?,?)";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            int batched = 0;
            for (Triple t : triples) {
                if (isBlank(t.subject()) || isBlank(t.object())) continue;
                ps.setString(1, t.subject());
                ps.setString(2, t.relation());
                ps.setString(3, t.object());
                ps.setString(4, norm(t.subject()));
                ps.setString(5, norm(t.object()));
                ps.setString(6, t.sourceId());
                ps.setString(7, t.tenantId());
                ps.setString(8, t.category());
                ps.addBatch();
                batched++;
            }
            if (batched > 0) ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("graph add failed", e);
        }
    }

    @Override
    public List<Triple> neighbors(Set<String> seedSurfaces, int maxHops, String tenant, String category) {
        if (seedSurfaces == null || seedSurfaces.isEmpty() || maxHops < 1) return List.of();

        Set<String> visited = new HashSet<>();
        Set<String> frontier = new HashSet<>();
        for (String s : seedSurfaces) {
            String n = norm(s);
            if (!n.isEmpty() && visited.add(n)) frontier.add(n);
        }

        // 保 hop 顺序 + 去重（key = 四元组语义身份）
        LinkedHashMap<String, Triple> collected = new LinkedHashMap<>();
        for (int hop = 0; hop < maxHops && !frontier.isEmpty(); hop++) {
            List<Triple> hits = queryFrontier(frontier, tenant, category);
            Set<String> next = new HashSet<>();
            for (Triple t : hits) {
                collected.putIfAbsent(identity(t), t);
                String sn = norm(t.subject());
                String on = norm(t.object());
                // 另一端点：当前 frontier 里命中的是 subject 还是 object 都可能，两端都尝试推进
                if (frontier.contains(sn) && visited.add(on)) next.add(on);
                if (frontier.contains(on) && visited.add(sn)) next.add(sn);
            }
            frontier = next;
        }
        return new ArrayList<>(collected.values());
    }

    private List<Triple> queryFrontier(Set<String> frontier, String tenant, String category) {
        List<String> norms = new ArrayList<>(frontier);
        String in = placeholders(norms.size());
        StringBuilder sql = new StringBuilder("SELECT subject, relation, object, source_id, tenant_id, category FROM ")
                .append(table)
                .append(" WHERE tenant_id = ? AND (subject_norm IN (").append(in)
                .append(") OR object_norm IN (").append(in).append("))");
        if (category != null) sql.append(" AND category = ?");

        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, tenant);
            for (String n : norms) ps.setString(idx++, n);   // subject_norm IN
            for (String n : norms) ps.setString(idx++, n);   // object_norm IN
            if (category != null) ps.setString(idx, category);
            try (ResultSet rs = ps.executeQuery()) {
                List<Triple> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Triple(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("graph neighbors query failed", e);
        }
    }

    @Override
    public Set<String> entities(String tenant, String category) {
        StringBuilder sql = new StringBuilder("SELECT subject, object FROM ").append(table)
                .append(" WHERE tenant_id = ?");
        if (category != null) sql.append(" AND category = ?");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setString(1, tenant);
            if (category != null) ps.setString(2, category);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> out = new HashSet<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                    out.add(rs.getString(2));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("graph entities query failed", e);
        }
    }

    @Override
    public int removeBySourcePrefix(String tenant, String sourceIdPrefix) {
        String sql = "DELETE FROM " + table + " WHERE tenant_id = ? AND source_id LIKE ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tenant);
            ps.setString(2, escapeLike(sourceIdPrefix) + "%");
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("graph remove failed", e);
        }
    }

    @Override
    public int size() {
        try (Connection c = conn(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("graph size query failed", e);
        }
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(Math.max(n, 1), "?"));
    }

    /** LIKE 通配转义：把 prefix 里的 %、_、\ 转义，避免 displayName 含这些字符时误删范围。 */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String identity(Triple t) {
        return t.subject() + "" + t.relation() + "" + t.object() + "" + t.sourceId();
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
