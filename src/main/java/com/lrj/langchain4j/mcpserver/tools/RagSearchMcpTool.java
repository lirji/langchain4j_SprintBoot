package com.lrj.langchain4j.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.langchain4j.mcpserver.McpServerTool;
import com.lrj.langchain4j.rag.TaggedSourceContentInjector;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 工具：企业知识库向量检索。复用主 RAG 链的 {@code vectorRetriever}（已带租户 + category 过滤的
 * {@code dynamicFilter}），因此外部 MCP 客户端调进来时仍按 {@link com.lrj.langchain4j.security.TenantContext}
 * 隔离——租户身份由 {@code ApiKeyAuthFilter} 在 {@code POST /mcp/server} 请求线程上绑定。
 *
 * <p>返回片段带 {@code [doc=ID]} 标记（{@link TaggedSourceContentInjector#inferId}），与主链引用格式一致，
 * 外部模型可据此在答案里标注来源。条件化在 {@code app.mcp.server.enabled=true}。
 */
@Component
@ConditionalOnProperty(name = "app.mcp.server.enabled", havingValue = "true")
public class RagSearchMcpTool implements McpServerTool {

    /** 单次检索回传的片段数上限（更多 retriever 自身按 top-k 截）。 */
    private static final int MAX_SNIPPETS = 5;
    /** 每个片段正文截断，防结果过长。 */
    private static final int MAX_SNIPPET_CHARS = 600;

    private final ContentRetriever retriever;

    public RagSearchMcpTool(@Qualifier("vectorRetriever") ContentRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public String name() {
        return "rag_search";
    }

    @Override
    public String description() {
        return "Search the enterprise knowledge base by semantic vector similarity. "
                + "Argument `query` is the keywords or question to look up. Returns up to 5 snippets "
                + "each tagged with [doc=ID]; cite these ids in your answer. "
                + "Use when you need factual / document grounding; skip for small talk or common knowledge.";
    }

    @Override
    public java.util.Map<String, Object> inputSchema() {
        return java.util.Map.of(
                "type", "object",
                "properties", java.util.Map.of(
                        "query", java.util.Map.of(
                                "type", "string",
                                "description", "Keywords or question to search the knowledge base for.")),
                "required", List.of("query"));
    }

    @Override
    public String call(JsonNode arguments) {
        String query = stringArg(arguments, "query");
        if (query == null || query.isBlank()) {
            return "检索词为空：arguments.query 请填要查的关键词或问题。";
        }
        List<Content> hits;
        try {
            hits = retriever.retrieve(Query.from(query.trim()));
        } catch (Exception e) {
            return "检索失败：" + e.getMessage() + "（可换个关键词重试）";
        }
        if (hits.isEmpty()) {
            return "知识库里没有检索到与「" + query.trim() + "」相关的资料。";
        }
        StringBuilder sb = new StringBuilder("检索到 " + Math.min(hits.size(), MAX_SNIPPETS) + " 条片段：\n");
        for (int i = 0; i < hits.size() && i < MAX_SNIPPETS; i++) {
            TextSegment seg = hits.get(i).textSegment();
            String id = TaggedSourceContentInjector.inferId(seg, i);
            String text = seg.text();
            if (text.length() > MAX_SNIPPET_CHARS) {
                text = text.substring(0, MAX_SNIPPET_CHARS) + "…";
            }
            sb.append("[doc=").append(id).append("] ").append(text).append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
