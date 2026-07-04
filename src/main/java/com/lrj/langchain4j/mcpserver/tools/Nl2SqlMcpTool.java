package com.lrj.langchain4j.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.langchain4j.mcpserver.McpServerTool;
import com.lrj.langchain4j.nl2sql.NlToSqlService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具：自然语言查业务库（只读 + 6 层 SQL 安全护栏 + 租户谓词）。透传受控的
 * {@link NlToSqlService}——护栏都在 service 内，工具只做协议适配，验证"高风险能力也能安全暴露给外部"。
 *
 * <p><strong>只在 {@code app.mcp.server.enabled} 且 {@code app.nl2sql.enabled} 同时为 true 时装配</strong>
 * （多 property 的 {@code @ConditionalOnProperty} 要求全部命中）——NL2SQL 关闭时这个工具根本不出现在
 * {@code tools/list} 里，外部客户端看不到、也无从调用（软依赖靠"条件化装配 + service 缺席则 bean 缺席"实现）。
 */
@Component
@ConditionalOnProperty(name = {"app.mcp.server.enabled", "app.nl2sql.enabled"}, havingValue = "true")
public class Nl2SqlMcpTool implements McpServerTool {

    /** 回传给模型的行数上限（更多行 NL2SQL 链自身已按 LIMIT 护栏截）。 */
    private static final int MAX_ROWS_ECHOED = 10;

    private final NlToSqlService nl2sql;

    public Nl2SqlMcpTool(NlToSqlService nl2sql) {
        this.nl2sql = nl2sql;
    }

    @Override
    public String name() {
        return "nl2sql_query";
    }

    @Override
    public String description() {
        return "Query the business database in natural language (read-only, guarded). "
                + "Argument `question` is the question to answer (e.g. \"last month's refund total\", "
                + "\"number of pending tickets\"). Returns the generated SQL, row count and data. "
                + "Use for live business metrics / stats; use rag_search for document questions.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "question", Map.of(
                                "type", "string",
                                "description", "Natural-language question about the business data.")),
                "required", List.of("question"));
    }

    @Override
    public String call(JsonNode arguments) {
        String question = stringArg(arguments, "question");
        if (question == null || question.isBlank()) {
            return "查询为空：arguments.question 请填要查的业务问题。";
        }
        NlToSqlService.Result r;
        try {
            r = nl2sql.ask(question.trim());
        } catch (Exception e) {
            return "查询失败：" + e.getMessage() + "（可换种问法重试）";
        }
        if (r.guardBlocked()) {
            return "查询被安全护栏拦截（疑似越权/非只读/越界），未执行。请换一个只读的、限定本租户数据的问法。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("SQL: ").append(r.sql() == null ? "(未生成)" : r.sql()).append("\n");
        sb.append("行数: ").append(r.rowCount()).append("\n");
        List<Map<String, Object>> rows = r.rows();
        if (!rows.isEmpty()) {
            sb.append("数据: ");
            for (int i = 0; i < rows.size() && i < MAX_ROWS_ECHOED; i++) {
                sb.append(rows.get(i));
                if (i < rows.size() - 1 && i < MAX_ROWS_ECHOED - 1) sb.append("; ");
            }
            if (rows.size() > MAX_ROWS_ECHOED) sb.append(" …(共 ").append(rows.size()).append(" 行)");
            sb.append("\n");
        }
        if (r.answer() != null && !r.answer().isBlank()) {
            sb.append("解读: ").append(r.answer());
        }
        return sb.toString().stripTrailing();
    }
}
