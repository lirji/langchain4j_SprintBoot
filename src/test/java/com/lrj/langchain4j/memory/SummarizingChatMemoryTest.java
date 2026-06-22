package com.lrj.langchain4j.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测 {@link SummarizingChatMemory} 的压缩行为：同步压缩成 summary、异步最终压缩、
 * 摘要失败不丢消息。用一个固定返回的假 summarizer（{@link ChatModel} 全 default 方法，只覆写 chat(String)）。
 */
class SummarizingChatMemoryTest {

    private static final String SUMMARY_PREFIX = "[Conversation summary so far]\n";

    /** 计数 + 可选抛错的假 summarizer。 */
    private static ChatModel fakeSummarizer(AtomicInteger calls, boolean fail) {
        return new ChatModel() {
            @Override
            public String chat(String message) {
                calls.incrementAndGet();
                if (fail) throw new RuntimeException("summarizer down");
                return "- bullet one\n- bullet two";
            }
        };
    }

    private static SummarizingChatMemory mem(Object id, ChatMemoryStore store, ChatModel s,
                                             int threshold, int keepRecent, java.util.concurrent.Executor exec) {
        return new SummarizingChatMemory(id, store, s, threshold, keepRecent, exec);
    }

    @Test
    void syncMode_compactsOldMessagesIntoSummary() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        AtomicInteger calls = new AtomicInteger();
        SummarizingChatMemory m = mem("c1", store, fakeSummarizer(calls, false), 4, 2, null);

        // 加 5 条：第 5 条触发 size(5) > threshold(4) → 同步压缩
        for (int i = 1; i <= 5; i++) {
            m.add(i % 2 == 1 ? UserMessage.from("u" + i) : AiMessage.from("a" + i));
        }
        List<ChatMessage> msgs = m.messages();
        // compactCount = 5 - keepRecent(2) = 3 → [summary] + 最近 2 条 = 3
        assertThat(msgs).hasSize(3);
        assertThat(msgs.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) msgs.get(0)).text()).startsWith(SUMMARY_PREFIX);
        assertThat(calls.get()).isEqualTo(1);
        // 最近 2 条原样保留（u5 在最后）
        assertThat(((UserMessage) msgs.get(2)).singleText()).isEqualTo("u5");
    }

    @Test
    void asyncMode_eventuallyCompacts_offRequestPath() throws Exception {
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        AtomicInteger calls = new AtomicInteger();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            SummarizingChatMemory m = mem("c2", store, fakeSummarizer(calls, false), 4, 2, exec);
            for (int i = 1; i <= 5; i++) {
                m.add(UserMessage.from("u" + i));
            }
            // 异步：压缩在后台线程，poll 等它收敛
            long deadline = System.currentTimeMillis() + 3000;
            while (m.messages().size() > 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(m.messages()).hasSize(3);
            assertThat(m.messages().get(0)).isInstanceOf(SystemMessage.class);
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            exec.shutdownNow();
            exec.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void summarizerFailure_leavesMessagesUncompacted_noLoss() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        AtomicInteger calls = new AtomicInteger();
        SummarizingChatMemory m = mem("c3", store, fakeSummarizer(calls, true), 4, 2, null);

        for (int i = 1; i <= 5; i++) {
            m.add(UserMessage.from("u" + i));
        }
        List<ChatMessage> msgs = m.messages();
        // 摘要抛错 → 不丢消息，全部 5 条保留、无 summary 头
        assertThat(msgs).hasSize(5);
        assertThat(msgs.get(0)).isNotInstanceOf(SystemMessage.class);
        assertThat(calls.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void tokenBudget_triggersCompactionUnderMessageCount() {
        // 条数阈值很高（100）→ 永不靠条数触发；maxTokens 很小（10）→ 几条长消息就靠 token 触发压缩
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        AtomicInteger calls = new AtomicInteger();
        var estimator = new dev.langchain4j.model.openai.OpenAiTokenCountEstimator("gpt-4o-mini");
        SummarizingChatMemory m = new SummarizingChatMemory("t1", store, fakeSummarizer(calls, false),
                100, 2, null, estimator, 10, 0);
        for (int i = 1; i <= 4; i++) {
            m.add(UserMessage.from("this is a fairly long user message number " + i + " to burn tokens"));
        }
        // 条数才 4（<100）但 token 早超 10 → 已压缩成 [summary] + 最近 2 条
        assertThat(m.messages()).hasSize(3);
        assertThat(m.messages().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(calls.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void summaryBloatCap_truncatesLongSummary() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        // summarizer 返回一段超长摘要，maxSummaryChars=20 → 截断
        ChatModel longSummarizer = new ChatModel() {
            @Override
            public String chat(String message) {
                return "x".repeat(500);
            }
        };
        SummarizingChatMemory m = new SummarizingChatMemory("t2", store, longSummarizer,
                4, 2, null, null, 0, 20);
        for (int i = 1; i <= 5; i++) {
            m.add(UserMessage.from("u" + i));
        }
        SystemMessage summary = (SystemMessage) m.messages().get(0);
        // 前缀 + 截断后的摘要(20) + 截断标记，远小于 500
        assertThat(summary.text()).contains("（摘要已截断）");
        assertThat(summary.text().length()).isLessThan(80);
    }

    @Test
    void clear_removesAllMessages() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        SummarizingChatMemory m = mem("c4", store, fakeSummarizer(new AtomicInteger(), false), 4, 2, null);
        m.add(UserMessage.from("hi"));
        assertThat(m.messages()).isNotEmpty();
        m.clear();
        assertThat(m.messages()).isEmpty();
    }
}
