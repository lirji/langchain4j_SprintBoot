package com.lrj.langchain4j.nl2sql;

import com.lrj.langchain4j.audit.AuditEventType;
import com.lrj.langchain4j.audit.AuditLogger;
import com.lrj.langchain4j.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NL2SQL 编排：注入 schema + 当前租户 → {@link SqlAssistant}（其间 {@link SqlQueryTool} 执行 SQL 并把
 * 执行记录写进 {@link SqlExecutionContext}）→ 取回本轮 SQL/rows 组装响应 → 审计。
 *
 * <p>由 {@code Nl2SqlConfig} 在 {@code app.nl2sql.enabled=true} 时构建。
 */
public class NlToSqlService {

    private static final Logger log = LoggerFactory.getLogger(NlToSqlService.class);

    private final SqlAssistant sqlAssistant;
    private final SchemaProvider schemaProvider;
    private final AuditLogger audit;
    private final boolean numberGrounding;

    public NlToSqlService(SqlAssistant sqlAssistant, SchemaProvider schemaProvider, AuditLogger audit,
                          boolean numberGrounding) {
        this.sqlAssistant = sqlAssistant;
        this.schemaProvider = schemaProvider;
        this.audit = audit;
        this.numberGrounding = numberGrounding;
    }

    /** {@code sql}/{@code rows} 取本轮最后一次成功执行；模型若拒答（没查库）则为 null/空。 */
    public record Result(String question, String sql, int rowCount,
                         List<Map<String, Object>> rows, String answer, boolean guardBlocked) {}

    public Result ask(String question) {
        String tenantId = TenantContext.current().tenantId();
        SqlExecutionContext.begin();
        try {
            String answer = sqlAssistant.answer(schemaProvider.schemaText(), tenantId, question);

            SqlExecutionContext.Execution last = SqlExecutionContext.lastSuccessful();
            boolean guardBlocked = SqlExecutionContext.get().stream().anyMatch(SqlExecutionContext.Execution::rejected);
            String sql = last == null ? null : last.sql();
            List<Map<String, Object>> rows = last == null ? List.of() : last.rows();

            // 数字 grounding（确定性、warn 模式）：仅在有数据可对照时跑（lastSuccessful 且非空）。
            if (numberGrounding && last != null && !rows.isEmpty()) {
                List<String> unsupported = NumberGrounding.unsupportedNumbers(answer, rows, question, rows.size());
                if (!unsupported.isEmpty()) {
                    log.warn("NL2SQL 数字核对告警（tenant={}）：答案数字未在结果中找到 {} | sql={}",
                            tenantId, unsupported, sql);
                    answer = answer + "\n\n⚠️ 数字核对提示：答案中的 "
                            + String.join("、", unsupported) + " 未在查询结果中找到，请以查询结果为准。";
                }
            }

            audit.record(AuditEventType.NL2SQL_QUERY, auditFields(question, sql, rows.size(), guardBlocked));
            return new Result(question, sql, rows.size(), rows, answer, guardBlocked);
        } finally {
            SqlExecutionContext.clear();
        }
    }

    private static Map<String, Object> auditFields(String question, String sql, int rowCount, boolean guardBlocked) {
        Map<String, Object> m = new HashMap<>();
        m.put("question", question);
        m.put("sql", sql);
        m.put("rowCount", rowCount);
        m.put("guardBlocked", guardBlocked);
        return m;
    }
}
