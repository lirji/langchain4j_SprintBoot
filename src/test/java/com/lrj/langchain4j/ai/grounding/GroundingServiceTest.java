package com.lrj.langchain4j.ai.grounding;

import com.lrj.langchain4j.config.GroundingProperties;
import com.lrj.langchain4j.rag.RetrievedSourcesContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测 grounding 后校验的确定性部分（Layer 0 引用核对 + 开关 + warn 拼接）。
 * Layer 1 的真实 LLM 调用用 lambda stub 顶替，因为 GroundednessChecker 是单方法接口。
 * 不拉 Spring 上下文、不发真请求。
 */
class GroundingServiceTest {

    private static final String WARN_MARK = "⚠️ 可信度提示";

    private GroundingProperties props(boolean enabled, double threshold) {
        GroundingProperties p = new GroundingProperties();
        p.setEnabled(enabled);
        p.setThreshold(threshold);
        return p;
    }

    /** 把"注入器写 source"模拟进 supplier —— 真实链路里由 TaggedSourceContentInjector 干。 */
    private static String answerWithSources(String answer, RetrievedSourcesContext.Source... sources) {
        RetrievedSourcesContext.set(List.of(sources));
        return answer;
    }

    /** GroundingService 只调 getIfAvailable()，直接返回 checker（null = 没装配 Bean）。 */
    private static ObjectProvider<GroundednessChecker> provide(GroundednessChecker checker) {
        return new ObjectProvider<>() {
            @Override
            public GroundednessChecker getIfAvailable() {
                return checker;
            }
            @Override
            public GroundednessChecker getObject() {
                return checker;
            }
            @Override
            public GroundednessChecker getObject(Object... args) {
                return checker;
            }
            @Override
            public GroundednessChecker getIfUnique() {
                return checker;
            }
        };
    }

    @Test
    void disabled_passesThroughEvenWithFabricatedCitation() {
        var svc = new GroundingService(props(false, 0.7), provide(null));
        String out = svc.applyToFreshAnswer(() ->
                answerWithSources("见 [doc=ghost.md#9]",
                        new RetrievedSourcesContext.Source("real.md#0", "真实内容")));
        assertThat(out).isEqualTo("见 [doc=ghost.md#9]");
        assertThat(out).doesNotContain(WARN_MARK);
    }

    @Test
    void noRetrieval_passesThrough() {
        var svc = new GroundingService(props(true, 0.7), provide(null));
        // supplier 不写 RetrievedSourcesContext —— 模拟本轮没触发 RAG
        String out = svc.applyToFreshAnswer(() -> "纯聊天，没检索");
        assertThat(out).isEqualTo("纯聊天，没检索");
    }

    @Test
    void layer0_validCitationOnly_noWarning() {
        // checker 给满分，确保只验 Layer 0
        var svc = new GroundingService(props(true, 0.7), provide((s, a) -> new GroundednessReport(1.0, List.of())));
        String out = svc.applyToFreshAnswer(() ->
                answerWithSources("答案见 [doc=real.md#0]",
                        new RetrievedSourcesContext.Source("real.md#0", "真实内容")));
        assertThat(out).doesNotContain(WARN_MARK);
    }

    @Test
    void layer0_fabricatedCitation_warns() {
        var svc = new GroundingService(props(true, 0.7), provide((s, a) -> new GroundednessReport(1.0, List.of())));
        String out = svc.applyToFreshAnswer(() ->
                answerWithSources("答案见 [doc=ghost.md#9]",
                        new RetrievedSourcesContext.Source("real.md#0", "真实内容")));
        assertThat(out).contains(WARN_MARK);
        assertThat(out).contains("引用了未检索到的来源：ghost.md#9");
    }

    @Test
    void layer1_lowFaithfulness_warns() {
        var svc = new GroundingService(props(true, 0.7),
                provide((s, a) -> new GroundednessReport(0.30, List.of("地球是平的"))));
        String out = svc.applyToFreshAnswer(() ->
                answerWithSources("[doc=real.md#0] 地球是平的",
                        new RetrievedSourcesContext.Source("real.md#0", "地球是球形")));
        assertThat(out).contains(WARN_MARK);
        assertThat(out).contains("grounding=0.30");
        assertThat(out).contains("地球是平的");
    }

    @Test
    void abstention_skipsGroundingEvenWithLowCheckerScore() {
        // checker 会给 0.0，但弃答不该被打可信度提示（实测 qwen3:8b 会把弃答误判成 0.0）
        var svc = new GroundingService(props(true, 0.7),
                provide((s, a) -> new GroundednessReport(0.0, List.of("未在文档中找到相关内容"))));
        String out = svc.applyToFreshAnswer(() ->
                answerWithSources("未在文档中找到相关内容",
                        new RetrievedSourcesContext.Source("real.md#0", "无关内容")));
        assertThat(out).isEqualTo("未在文档中找到相关内容");
        assertThat(out).doesNotContain(WARN_MARK);
    }

    @Test
    void layer1_checkerFailure_doesNotBreakAnswer() {
        var svc = new GroundingService(props(true, 0.7), provide((s, a) -> {
            throw new RuntimeException("LLM 挂了");
        }));
        String out = svc.applyToFreshAnswer(() ->
                answerWithSources("答案见 [doc=real.md#0]",
                        new RetrievedSourcesContext.Source("real.md#0", "真实内容")));
        // Layer 1 异常被吞，Layer 0 通过 → 原样返回
        assertThat(out).isEqualTo("答案见 [doc=real.md#0]");
    }

    @Test
    void clearsThreadLocalAfterRun() {
        var svc = new GroundingService(props(true, 0.7), provide((s, a) -> new GroundednessReport(1.0, List.of())));
        svc.applyToFreshAnswer(() ->
                answerWithSources("x [doc=real.md#0]",
                        new RetrievedSourcesContext.Source("real.md#0", "c")));
        assertThat(RetrievedSourcesContext.get()).isNull();
    }
}
