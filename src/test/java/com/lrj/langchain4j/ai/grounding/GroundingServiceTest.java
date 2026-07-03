package com.lrj.langchain4j.ai.grounding;

import com.lrj.langchain4j.config.GroundingProperties;
import com.lrj.langchain4j.rag.RetrievedSourcesContext;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    private GroundingProperties props(boolean enabled, double threshold,
                                      GroundingProperties.OnFail onFail, int maxRegen) {
        GroundingProperties p = props(enabled, threshold);
        p.setOnFail(onFail);
        p.setMaxRegenerations(maxRegen);
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
    void refuseMode_fabricatedCitation_replacesAnswerWithSafeMessage() {
        var svc = new GroundingService(props(true, 0.7, GroundingProperties.OnFail.REFUSE, 1),
                provide((s, a) -> new GroundednessReport(1.0, List.of())));
        String out = svc.applyToFreshAnswer(() ->
                answerWithSources("答案见 [doc=ghost.md#9]",
                        new RetrievedSourcesContext.Source("real.md#0", "真实内容")));
        assertThat(out).doesNotContain("ghost.md#9");   // 未被支撑的内容被整段替换
        assertThat(out).doesNotContain(WARN_MARK);       // refuse 不是 warn
        assertThat(out).contains("暂不作答");
    }

    @Test
    void regenerateMode_secondAttemptGrounded_returnsCleanAnswer() {
        AtomicInteger n = new AtomicInteger();
        var svc = new GroundingService(props(true, 0.7, GroundingProperties.OnFail.REGENERATE, 1),
                provide((s, a) -> new GroundednessReport(1.0, List.of())));
        String out = svc.applyToFreshAnswer(hint -> {
            if (n.getAndIncrement() == 0) {
                return answerWithSources("答案见 [doc=ghost.md#9]",   // 首次编造引用
                        new RetrievedSourcesContext.Source("real.md#0", "真实内容"));
            }
            assertThat(hint).isNotEmpty();                            // 重生成时带纠正指令
            return answerWithSources("答案见 [doc=real.md#0]",         // 修正后引用正确
                    new RetrievedSourcesContext.Source("real.md#0", "真实内容"));
        });
        assertThat(out).isEqualTo("答案见 [doc=real.md#0]");
        assertThat(out).doesNotContain(WARN_MARK);
        assertThat(n.get()).isEqualTo(2);
    }

    @Test
    void regenerateMode_exhausted_degradesToWarn() {
        AtomicInteger n = new AtomicInteger();
        var svc = new GroundingService(props(true, 0.7, GroundingProperties.OnFail.REGENERATE, 1),
                provide((s, a) -> new GroundednessReport(1.0, List.of())));
        String out = svc.applyToFreshAnswer(hint -> {
            n.incrementAndGet();
            return answerWithSources("答案见 [doc=ghost.md#9]",       // 始终编造 → 重试也不过
                    new RetrievedSourcesContext.Source("real.md#0", "真实内容"));
        });
        assertThat(out).contains(WARN_MARK);       // 耗尽后降级为 warn
        assertThat(out).contains("ghost.md#9");     // 保留最佳尝试
        assertThat(n.get()).isEqualTo(2);           // 1 初始 + 1 重生成
    }

    /** 用 file_name + index metadata 造 Content，inferId 会得到 "file#index" 形式的稳定 id。 */
    private static Content content(String fileName, String index, String text) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of("file_name", fileName, "index", index))));
    }

    @Test
    void streamWarning_fabricatedCitation_returnsSuffix() {
        // 流式路径：source 由 onRetrieved 捕获的 Content 传入（非 ThreadLocal）
        var svc = new GroundingService(props(true, 0.7), provide((s, a) -> new GroundednessReport(1.0, List.of())));
        String suffix = svc.streamWarningOrNull(
                List.of(content("real.md", "0", "真实内容")),
                "答案见 [doc=ghost.md#9]");
        assertThat(suffix).isNotNull().contains(WARN_MARK).contains("ghost.md#9");
    }

    @Test
    void streamWarning_validCitation_null() {
        var svc = new GroundingService(props(true, 0.7), provide((s, a) -> new GroundednessReport(1.0, List.of())));
        String suffix = svc.streamWarningOrNull(
                List.of(content("real.md", "0", "真实内容")),
                "答案见 [doc=real.md#0]");
        assertThat(suffix).isNull();
    }

    @Test
    void streamWarning_disabledOrNoRetrieval_null() {
        var off = new GroundingService(props(false, 0.7), provide(null));
        assertThat(off.streamWarningOrNull(List.of(content("real.md", "0", "c")), "[doc=ghost.md#9]")).isNull();
        var on = new GroundingService(props(true, 0.7), provide(null));
        assertThat(on.streamWarningOrNull(List.of(), "[doc=ghost.md#9]")).isNull();
        assertThat(on.streamWarningOrNull(null, "x")).isNull();
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
