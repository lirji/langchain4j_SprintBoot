package com.lrj.langchain4j.ai.agent.actions;

import com.lrj.langchain4j.ai.agent.AgentAction;
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
 * 深度 Agent 动作：在企业知识库里做向量检索，返回带 source id 的片段供模型在最终答案里引用。
 * 复用主 RAG 链的 {@code vectorRetriever}（已带租户 + category 过滤的 {@code dynamicFilter}），
 * 因此跑在子线程里也按 {@code TenantContext} 隔离——验证「RAG 不止能挂在 {@code Assistant} 的自动
 * augmentor 上，也能作为一个显式动作插进 ReAct 循环，让模型自己决定何时检索」。
 *
 * <p>条件化在 {@code app.deep-agent.enabled=true}。source id 用 {@link TaggedSourceContentInjector#inferId}
 * 生成，与主链 {@code [doc=ID]} 引用格式一致。
 */
@Component
@ConditionalOnProperty(name = "app.deep-agent.enabled", havingValue = "true")
public class RagSearchAction implements AgentAction {

    /** 单次检索回传给模型的片段数上限（再多 retriever 自身也按 top-k 截）。 */
    private static final int MAX_SNIPPETS = 5;
    /** 每个片段正文截断，防 scratchpad 爆。 */
    private static final int MAX_SNIPPET_CHARS = 600;

    private final ContentRetriever retriever;

    public RagSearchAction(@Qualifier("vectorRetriever") ContentRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public String name() {
        return "rag_search";
    }

    @Override
    public String description() {
        return "在企业知识库里检索资料；actionInput 填要查的关键词或问题。返回若干带 [doc=ID] 标记的片段，"
                + "在最终答案里引用这些 id。需要事实/文档依据时用，闲聊或常识题不要用。";
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            return "检索词为空：actionInput 请填要查的关键词或问题。";
        }
        List<Content> hits;
        try {
            hits = retriever.retrieve(Query.from(input.trim()));
        } catch (Exception e) {
            return "检索失败：" + e.getMessage() + "（可换个关键词重试或改走其他动作）";
        }
        if (hits.isEmpty()) {
            return "知识库里没有检索到与「" + input.trim() + "」相关的资料。";
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
