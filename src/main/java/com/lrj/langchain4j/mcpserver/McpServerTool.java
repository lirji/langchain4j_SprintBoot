package com.lrj.langchain4j.mcpserver;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 一个可被外部 MCP 客户端（Claude Desktop / Cursor 等）调用的能力单元。
 *
 * <p>本项目 <strong>反向</strong>暴露自身能力：{@code ai/mcp}（{@code McpClient}）是把外部 MCP server
 * 的工具桥进来给模型用；这里是把本 app 的能力（时间 / RAG / NL2SQL）桥出去给外部 agent 用。
 *
 * <p>每个实现是一个 {@code @Component}（条件化在 {@code app.mcp.server.enabled=true}，可再叠加更多
 * property），由 {@link McpServerService} 自动收集成 {@code List<McpServerTool>}——加新能力 = 新增一个
 * 实现类，无需改 service / controller（同 {@code ai/agent} 的 {@code AgentAction} 自动发现范式）。
 *
 * <p>租户隔离：{@code call} 跑在处理 {@code POST /mcp/server} 的请求线程上，
 * {@link com.lrj.langchain4j.security.TenantContext} 已由 {@code ApiKeyAuthFilter} 绑定，
 * 各实现复用现有带租户过滤的下游（如 {@code vectorRetriever} / {@code NlToSqlService}）即自动隔离。
 */
public interface McpServerTool {

    /** MCP 工具唯一名，客户端按此名 {@code tools/call}。用 snake_case，与 {@code AgentAction} 命名一致。 */
    String name();

    /** 自然语言描述：模型据此决定"何时调用"，务必写清用途 / 时机 / 参数语义。 */
    String description();

    /** JSON Schema（{@code {"type":"object","properties":{...},"required":[...]}}），用于 {@code tools/list}。 */
    Map<String, Object> inputSchema();

    /**
     * 执行工具，返回给模型看的纯文本结果。
     *
     * <p>约定：<strong>业务层失败请返回可纠错文本、不要抛异常</strong>（与 {@code DateTimeTool} 一致）——
     * service 会把返回文本包成 {@code isError=false} 的 result。抛出的异常由 service 兜底成
     * {@code isError=true} 的 result（不打断 JSON-RPC 信封）。
     *
     * @param arguments {@code tools/call} 的 {@code arguments} 对象，可能为 {@code null}
     */
    String call(JsonNode arguments) throws Exception;

    /** 从 arguments 里取一个字符串字段的小工具（缺失 / 非文本返回 {@code null}）。 */
    default String stringArg(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) return null;
        JsonNode n = arguments.get(field);
        return n.isValueNode() ? n.asText() : null;
    }
}
