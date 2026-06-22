package com.lrj.langchain4j.nl2sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-request 持有本轮 {@link SqlQueryTool} 实际执行（或被护栏拒绝）的记录。
 *
 * <p>函数调用模式下，SQL 是模型在工具回合里生成并由工具执行的，{@link NlToSqlService} 拿不到。
 * 工具把每次执行写进这里，service 在 {@code SqlAssistant.answer(...)} 返回后读取，用来：
 * <ul>
 *   <li>组装响应 {@code {sql, rows, answer}}（前端可审计 / 复跑）</li>
 *   <li>（2.B）数字 grounding：核对答案里的数字 ∈ rows，仿 grounding Layer 0 的确定性套路</li>
 * </ul>
 *
 * <p>工具调用与 {@code answer(...)} 在同一线程（AiServices 同步执行工具回合），ThreadLocal 可正确传递。
 * 调用方负责 try/finally clear，防线程复用串数据。仿 {@link com.lrj.langchain4j.rag.RetrievedSourcesContext}。
 */
public final class SqlExecutionContext {

    public record Execution(String sql, List<Map<String, Object>> rows, boolean rejected, String reason) {}

    private static final ThreadLocal<List<Execution>> CURRENT = new ThreadLocal<>();

    private SqlExecutionContext() {}

    public static void begin() {
        CURRENT.set(new ArrayList<>());
    }

    public static void add(Execution e) {
        List<Execution> list = CURRENT.get();
        if (list != null) {
            list.add(e);
        }
    }

    public static List<Execution> get() {
        List<Execution> list = CURRENT.get();
        return list == null ? List.of() : list;
    }

    /** 本轮最后一次成功执行（用于响应里的 sql + rows）；没有则 null。 */
    public static Execution lastSuccessful() {
        Execution found = null;
        for (Execution e : get()) {
            if (!e.rejected()) {
                found = e;
            }
        }
        return found;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
