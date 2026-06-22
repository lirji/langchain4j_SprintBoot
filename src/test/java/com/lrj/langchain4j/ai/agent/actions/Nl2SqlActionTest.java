package com.lrj.langchain4j.ai.agent.actions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.audit.AuditLogger;
import com.lrj.langchain4j.nl2sql.NlToSqlService;
import com.lrj.langchain4j.nl2sql.Nl2SqlProperties;
import com.lrj.langchain4j.nl2sql.SchemaProvider;
import com.lrj.langchain4j.nl2sql.SqlAssistant;
import com.lrj.langchain4j.nl2sql.SqlExecutionContext;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Nl2SqlAction} 的确定性单测（不连模型/DB）：用桩 {@link SqlAssistant} 在 {@code answer()} 里手动写
 * {@link SqlExecutionContext}（模拟 {@code SqlQueryTool} 执行 SQL 的副作用），驱动真实 {@link NlToSqlService}
 * 走完 {@code ask()}，验证空入参守卫 / 护栏拦截分支 / 正常结果格式（SQL+行数+解读）/ 异常降级。
 *
 * <p>{@link SchemaProvider} 用一个 {@code getConnection} 抛异常的 {@code DataSource} 构造——其 {@code build()}
 * 吞异常返回空 schema，正好满足桩需求（桩 assistant 不看 schema）。{@code TenantContext} 不 set 时回退 anonymous。
 */
class Nl2SqlActionTest {

    /** getConnection 即抛的 DataSource → SchemaProvider 返回空 schema。 */
    private static SchemaProvider stubSchema() {
        javax.sql.DataSource ds = new javax.sql.DataSource() {
            @Override public java.sql.Connection getConnection() throws SQLException { throw new SQLException("no db"); }
            @Override public java.sql.Connection getConnection(String u, String p) throws SQLException { throw new SQLException("no db"); }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> iface) { return null; }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
        return new SchemaProvider(ds, new Nl2SqlProperties());
    }

    private static NlToSqlService service(SqlAssistant assistant) {
        return new NlToSqlService(assistant, stubSchema(), new AuditLogger(new ObjectMapper()), false);
    }

    @Test
    void blankInput_returnsCorrectableHint() {
        Nl2SqlAction action = new Nl2SqlAction(service((schema, tenant, q) -> "n/a"));
        assertEquals("nl2sql_query", action.name());
        assertTrue(action.run("  ").contains("为空"));
    }

    @Test
    void successfulQuery_formatsSqlRowsAndAnswer() {
        SqlAssistant assistant = (schema, tenant, q) -> {
            SqlExecutionContext.add(new SqlExecutionContext.Execution(
                    "SELECT count(*) AS c FROM orders",
                    List.of(Map.of("c", 7)), false, null));
            return "本租户共有 7 笔订单。";
        };
        String obs = new Nl2SqlAction(service(assistant)).run("有多少订单");
        assertTrue(obs.contains("SELECT count(*)"), "应回传生成的 SQL");
        assertTrue(obs.contains("行数: 1"));
        assertTrue(obs.contains("7 笔订单"), "应带解读");
    }

    @Test
    void guardBlocked_reportsRejection() {
        SqlAssistant assistant = (schema, tenant, q) -> {
            SqlExecutionContext.add(new SqlExecutionContext.Execution(
                    "DROP TABLE orders", List.of(), true, "non-readonly"));
            return "拒绝执行。";
        };
        String obs = new Nl2SqlAction(service(assistant)).run("删库");
        assertTrue(obs.contains("护栏拦截"), "护栏拦截应明确告知模型换问法");
        assertFalse(obs.contains("行数:"), "被拦截时不展示行数");
    }

    @Test
    void assistantThrows_degradesToText() {
        SqlAssistant boom = (schema, tenant, q) -> { throw new RuntimeException("model down"); };
        String obs = new Nl2SqlAction(service(boom)).run("x");
        assertTrue(obs.contains("查询失败"), "异常应降级成可纠错文本");
        assertTrue(obs.contains("model down"));
    }
}
