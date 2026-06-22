package com.lrj.langchain4j.ai.agent.actions;

import com.lrj.langchain4j.ai.agent.AgentAction;
import com.lrj.langchain4j.nl2sql.NlToSqlService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 深度 Agent 动作：把自然语言问题交给受控的 NL2SQL 链（6 层 SQL 安全护栏 + 只读执行 + 租户谓词），
 * 拿回 SQL + 行数 + 解读。让模型在长程任务里能「查业务库的实时数据」——验证带护栏的高风险能力
 * 也能安全地作为一个动作插进循环（护栏在 {@link NlToSqlService} 内，动作只透传）。
 *
 * <p><strong>只在 {@code app.deep-agent.enabled} 且 {@code app.nl2sql.enabled} 同时为 true 时装配</strong>
 * （多 property 的 {@code @ConditionalOnProperty} 要求全部命中）——NL2SQL 关闭时这个动作根本不出现在
 * 可用清单里，模型不会尝试调用一个不存在的能力。
 */
@Component
@ConditionalOnProperty(name = {"app.deep-agent.enabled", "app.nl2sql.enabled"}, havingValue = "true")
public class Nl2SqlAction implements AgentAction {

    /** 回传给模型的行数上限（更多行 NL2SQL 链自身已按 LIMIT 护栏截）。 */
    private static final int MAX_ROWS_ECHOED = 10;

    private final NlToSqlService nl2sql;

    public Nl2SqlAction(NlToSqlService nl2sql) {
        this.nl2sql = nl2sql;
    }

    @Override
    public String name() {
        return "nl2sql_query";
    }

    @Override
    public String description() {
        return "用自然语言查业务数据库（只读、带安全护栏）；actionInput 填要查的问题（如「上月退款总额」「待处理工单数」）。"
                + "返回生成的 SQL、命中行数和数据。需要库里的实时业务数据/统计时用，文档类问题用 rag_search。";
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            return "查询为空：actionInput 请填要查的业务问题。";
        }
        NlToSqlService.Result r;
        try {
            r = nl2sql.ask(input.trim());
        } catch (Exception e) {
            return "查询失败：" + e.getMessage() + "（可换种问法重试或改走其他动作）";
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
