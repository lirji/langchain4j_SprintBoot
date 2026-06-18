package com.lrj.langchain4j.rag.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmEntityLinker 确定性逻辑单测（stub 抽取器，不连模型）：把模型抽出的提及锚定到图中真实实体，
 * 只返回图里存在的表面形式；抽取失败/无提及降级返回空。
 */
class LlmEntityLinkerTest {

    private static final String T1 = "t1";

    private InMemoryGraphStore graph() {
        InMemoryGraphStore g = new InMemoryGraphStore();
        g.add(List.of(
                new Triple("张三", "隶属于", "华东大区", "s#0", T1, null)));
        return g;
    }

    private static QueryEntityExtractor stub(String... mentions) {
        return query -> new QueryEntities(List.of(mentions));
    }

    @Test
    void anchorsMentionToRealEntity_viaSubstring() {
        // 模型抽出「张三经理」（图里没有），靠互为子串锚定到真实实体「张三」
        LlmEntityLinker linker = new LlmEntityLinker(stub("张三经理"), graph());
        assertThat(linker.link("张三经理归谁管？", T1, null)).containsExactly("张三");
    }

    @Test
    void hallucinatedMention_notInGraph_yieldsNoSeed() {
        // 模型臆造一个图里完全不存在的实体 → 不返回任何种子（不拿幻觉当种子）
        LlmEntityLinker linker = new LlmEntityLinker(stub("王五"), graph());
        assertThat(linker.link("王五是谁？", T1, null)).isEmpty();
    }

    @Test
    void extractorThrows_degradesToEmpty() {
        QueryEntityExtractor boom = query -> { throw new RuntimeException("llm down"); };
        LlmEntityLinker linker = new LlmEntityLinker(boom, graph());
        assertThat(linker.link("张三的情况", T1, null)).isEmpty();   // 降级，不打挂检索
    }

    @Test
    void noMention_returnsEmpty() {
        LlmEntityLinker linker = new LlmEntityLinker(stub(), graph());
        assertThat(linker.link("随便问问", T1, null)).isEmpty();
    }
}
