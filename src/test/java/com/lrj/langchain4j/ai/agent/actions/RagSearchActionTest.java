package com.lrj.langchain4j.ai.agent.actions;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RagSearchAction} 的确定性单测（不连模型/向量库）：用假 {@link ContentRetriever} 喂预设片段，
 * 验证空入参守卫 / 命中格式（带 [doc=ID]，id 与主链一致）/ 截断 / 空命中 / 异常降级成可纠错文本。
 */
class RagSearchActionTest {

    private static Content content(String text, String fileName, String index) {
        Metadata m = Metadata.from(Map.of("file_name", fileName, "index", index));
        return Content.from(TextSegment.from(text, m));
    }

    @Test
    void blankInput_returnsCorrectableHint() {
        var action = new RagSearchAction(q -> List.of());
        assertEquals("rag_search", action.name());
        assertTrue(action.run("  ").contains("为空"));
    }

    @Test
    void hits_formattedWithDocCitations() {
        ContentRetriever retriever = q -> {
            assertEquals("退款政策", q.text());
            return List.of(content("七天内可无理由退款。", "policy.md", "2"));
        };
        String obs = new RagSearchAction(retriever).run("退款政策");
        assertTrue(obs.contains("[doc=policy.md#2]"), "片段应带与主链一致的 source id");
        assertTrue(obs.contains("七天内可无理由退款"));
    }

    @Test
    void longSnippet_truncated() {
        String big = "甲".repeat(1000);
        ContentRetriever retriever = q -> List.of(content(big, "big.md", "0"));
        String obs = new RagSearchAction(retriever).run("x");
        assertTrue(obs.contains("…"), "超长片段应被截断");
        assertFalse(obs.contains("甲".repeat(1000)), "不应原样回传全文");
    }

    @Test
    void emptyHits_saysNotFound() {
        String obs = new RagSearchAction(q -> List.of()).run("不存在的东西");
        assertTrue(obs.contains("没有检索到"));
    }

    @Test
    void retrieverThrows_degradesToText() {
        ContentRetriever boom = q -> { throw new RuntimeException("store down"); };
        String obs = new RagSearchAction(boom).run("x");
        assertTrue(obs.contains("检索失败"), "异常应降级成可纠错文本而非抛出");
        assertTrue(obs.contains("store down"));
    }
}
