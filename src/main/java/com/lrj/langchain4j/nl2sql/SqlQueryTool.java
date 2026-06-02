package com.lrj.langchain4j.nl2sql;

import com.lrj.langchain4j.security.TenantContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * NL2SQL 的只读执行工具。<strong>故意不是 Spring {@code @Component}/{@code @Bean}</strong> ——
 * 否则 langchain4j-spring-boot-starter 会把它当 {@code @Tool} bean 自动挂到主 {@code Assistant} 上，
 * 污染普通对话。由 {@code Nl2SqlConfig} {@code new} 出来后只挂给 {@link SqlAssistant}（{@code AiServices.builder().tools(...)}）。
 *
 * <p>执行前过 {@link SqlGuard}（L2–L4/L6）；执行用的 {@link JdbcTemplate} 绑定**只读 DB 账号**（L1）
 * 且设了 statement 超时（L5）。被护栏拒 / 执行报错时<strong>返回错误文本而非抛异常</strong>，
 * 让模型在下一个工具回合自行改写重试（为 2.B 的"自修环"打基础）。
 */
public class SqlQueryTool {

    private static final Logger log = LoggerFactory.getLogger(SqlQueryTool.class);
    private static final int MAX_RENDER_ROWS = 100;

    private final JdbcTemplate readOnlyJdbc;
    private final SqlGuard guard;

    public SqlQueryTool(JdbcTemplate readOnlyJdbc, SqlGuard guard) {
        this.readOnlyJdbc = readOnlyJdbc;
        this.guard = guard;
    }

    @Tool("""
            Run a single read-only SQL SELECT against the business database and return the rows.

            Use this for ANY question about business data (orders, customers, refunds —
            counts, sums, top-N, trends, lookups). Always answer data questions by querying,
            never from memory or guesses.

            Rules the SQL MUST follow (the query is checked by a safety guard; if it violates
            a rule the call returns an error and you should rewrite and call again):
            - A single SELECT statement only. No INSERT/UPDATE/DELETE/DDL, no semicolons,
              no comments, no UNION.
            - Only query the tables shown in the provided schema.
            - For tenant-scoped tables you MUST filter by the exact tenant id given in the
              system prompt, e.g. WHERE tenant_id = '<tenantId>'.
            - Use the column comments and the listed allowed values (e.g. Chinese status
              enums) so your WHERE clauses match the real data.

            Parameter `sql` is the complete SELECT statement.
            """)
    public String runSql(@P("A single read-only SELECT statement") String sql) {
        String tenantId = TenantContext.current().tenantId();
        SqlGuard.GuardResult gr = guard.check(sql, tenantId);
        if (!gr.allowed()) {
            SqlExecutionContext.add(new SqlExecutionContext.Execution(sql, List.of(), true, gr.reason()));
            log.warn("NL2SQL guard 拒绝 SQL（tenant={}）：{} | sql={}", tenantId, gr.reason(), sql);
            return "Query rejected by safety guard: " + gr.reason()
                    + ". Rewrite the SQL to comply and call run_sql again.";
        }
        try {
            List<Map<String, Object>> rows = readOnlyJdbc.queryForList(gr.sql());
            SqlExecutionContext.add(new SqlExecutionContext.Execution(gr.sql(), rows, false, null));
            return renderMarkdown(rows);
        } catch (Exception e) {
            SqlExecutionContext.add(new SqlExecutionContext.Execution(gr.sql(), List.of(), true, e.getMessage()));
            log.warn("NL2SQL 执行失败（tenant={}）：{} | sql={}", tenantId, e.getMessage(), gr.sql());
            return "Query failed: " + e.getMessage() + ". Fix the SQL and call run_sql again.";
        }
    }

    private static String renderMarkdown(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "(0 rows)";
        }
        List<String> cols = List.copyOf(rows.get(0).keySet());
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", cols)).append(" |\n");
        sb.append("| ").append(cols.stream().map(c -> "---").reduce((a, b) -> a + " | " + b).orElse("---")).append(" |\n");
        int shown = Math.min(rows.size(), MAX_RENDER_ROWS);
        for (int i = 0; i < shown; i++) {
            Map<String, Object> row = rows.get(i);
            sb.append("| ");
            for (int j = 0; j < cols.size(); j++) {
                Object v = row.get(cols.get(j));
                sb.append(v == null ? "" : v.toString());
                if (j < cols.size() - 1) sb.append(" | ");
            }
            sb.append(" |\n");
        }
        if (rows.size() > shown) {
            sb.append("... (").append(rows.size() - shown).append(" more rows)\n");
        }
        return sb.toString().strip();
    }
}
