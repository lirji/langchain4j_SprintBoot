package com.lrj.langchain4j.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

/**
 * ChatMemory that compacts old messages into a single {@link SystemMessage} summary when the
 * message count exceeds {@code threshold}. The most recent {@code keepRecent} messages are kept
 * verbatim so the model always sees fresh context.
 * <p>
 * Tradeoffs vs MessageWindow / TokenWindow:
 * <ul>
 *   <li>+ keeps long-term facts that a pure sliding window would drop</li>
 *   <li>-每次压缩是一次额外 LLM 调用（成本）</li>
 *   <li>- summary quality depends on the model; can drop nuance</li>
 * </ul>
 *
 * <p><strong>异步压缩</strong>：给了 {@code compactionExecutor} 时，压缩的 LLM 调用<strong>不在
 * {@link #add} 的请求路径上同步执行</strong> —— {@code add} 只追加消息立即返回，超阈值时把压缩任务
 * 投到后台线程池。这样请求线程不被那一次摘要 LLM 调用阻塞（原来会卡几百 ms ~ 秒级）。
 * 代价是 bound 变「软」：压缩完成前 {@link #messages()} 可能短暂返回略超 threshold 的列表（下一轮收敛）。
 * <p>
 * 后台压缩的并发设计：
 * <ol>
 *   <li><strong>LLM 调用在锁外</strong>：先在 per-id 锁内取快照，释放锁后调 LLM 摘要（慢），再进锁把
 *       prefix 换成 summary、保留期间新追加的尾部消息。避免摘要期间堵住同会话的其它 {@code add}</li>
 *   <li><strong>单飞</strong>：{@link #COMPACTING} 保证同一 id 同时只有一个压缩在跑，adds 期间不会堆叠</li>
 *   <li>合并时重读最新列表：若期间被 {@link #clear} 缩短则放弃合并；消息是 append-only，prefix 稳定</li>
 *   <li>LLM 失败 → 放弃本次（保留未压缩列表，下次 add 重试），不静默丢消息</li>
 * </ol>
 * {@code compactionExecutor == null} 时退回同步压缩（在 {@code add} 内、请求路径上），用于测试或未配置线程池。
 */
public class SummarizingChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(SummarizingChatMemory.class);
    private static final String SUMMARY_PREFIX = "[Conversation summary so far]\n";

    /**
     * Per-{@code id} 锁，串行化对同一会话的 read-modify-write。{@link #add} 的
     * {@code getMessages → 追加 → updateMessages} 不是原子的：同一 {@code chatId}
     * 的并发请求（A2A / Webhook / 多标签页）会互相覆盖中间状态，丢消息。锁按 id 维度，不同会话不互斥。
     * <p>
     * <strong>限于单 JVM</strong>：多副本部署 + Redis store 时跨进程仍可能丢更新，需 Redis 层
     * （WATCH/MULTI 乐观锁或分布式锁）。本进程锁先消除最常见的单实例并发丢失。
     */
    private static final ConcurrentHashMap<Object, Object> LOCKS = new ConcurrentHashMap<>();

    /** 单飞：正在后台压缩的 id 集合，防止同会话压缩任务堆叠。 */
    private static final Set<Object> COMPACTING = ConcurrentHashMap.newKeySet();

    private final Object id;
    private final ChatMemoryStore store;
    private final ChatModel summarizer;
    private final int threshold;
    private final int keepRecent;
    private final Executor compactionExecutor;
    /** 非 null 且 {@code maxTokens>0} 时，额外按 token 预算触发压缩（治「几条超大消息撑爆上下文但条数没超」）。 */
    private final TokenCountEstimator estimator;
    private final int maxTokens;
    /** 摘要膨胀上限（字符）：压出的摘要超过此长度就截断兜底，防多轮累积压缩越滚越大。0 = 不限。 */
    private final int maxSummaryChars;

    /** 同步压缩（无线程池）—— 兼容旧用法 / 测试。 */
    public SummarizingChatMemory(Object id, ChatMemoryStore store, ChatModel summarizer,
                                 int threshold, int keepRecent) {
        this(id, store, summarizer, threshold, keepRecent, null);
    }

    /**
     * @param compactionExecutor 非 null 时压缩在该线程池后台跑，不阻塞 {@link #add}；null 时同步压缩
     */
    public SummarizingChatMemory(Object id, ChatMemoryStore store, ChatModel summarizer,
                                 int threshold, int keepRecent, Executor compactionExecutor) {
        this(id, store, summarizer, threshold, keepRecent, compactionExecutor, null, 0, 0);
    }

    /**
     * @param estimator       非 null + {@code maxTokens>0} 时按 token 预算触发压缩（与条数阈值取或）
     * @param maxTokens       token 触发预算；0 = 关闭 token 计量（仅按条数）
     * @param maxSummaryChars 摘要最大字符数（膨胀上限），超出截断；0 = 不限
     */
    public SummarizingChatMemory(Object id, ChatMemoryStore store, ChatModel summarizer,
                                 int threshold, int keepRecent, Executor compactionExecutor,
                                 TokenCountEstimator estimator, int maxTokens, int maxSummaryChars) {
        if (keepRecent >= threshold) {
            throw new IllegalArgumentException("keepRecent must be < threshold");
        }
        this.id = id;
        this.store = store;
        this.summarizer = summarizer;
        this.threshold = threshold;
        this.keepRecent = keepRecent;
        this.compactionExecutor = compactionExecutor;
        this.estimator = estimator;
        this.maxTokens = maxTokens;
        this.maxSummaryChars = maxSummaryChars;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        boolean overThreshold;
        // 锁内只做快速的追加 + 写，绝不在这里调 LLM。
        synchronized (lock()) {
            List<ChatMessage> current = new ArrayList<>(store.getMessages(id));
            current.add(message);
            store.updateMessages(id, current);
            // 条数超阈 OR（开了 token 计量时）token 预算超阈 —— 任一触发压缩。
            overThreshold = current.size() > threshold
                    || (estimator != null && maxTokens > 0 && estimatedTokens(current) > maxTokens);
        }
        if (overThreshold) {
            triggerCompaction();
        }
    }

    private int estimatedTokens(List<ChatMessage> messages) {
        try {
            return estimator.estimateTokenCountInMessages(messages);
        } catch (Exception e) {
            return 0;   // 估算失败不阻断，退回纯条数触发
        }
    }

    @Override
    public List<ChatMessage> messages() {
        return store.getMessages(id);
    }

    @Override
    public void clear() {
        synchronized (lock()) {
            store.deleteMessages(id);
        }
        LOCKS.remove(id);
    }

    private Object lock() {
        return LOCKS.computeIfAbsent(id, k -> new Object());
    }

    /** 投递压缩：有线程池走后台 + 单飞；否则同步执行（请求路径上）。 */
    private void triggerCompaction() {
        if (compactionExecutor == null) {
            compactNow();
            return;
        }
        if (!COMPACTING.add(id)) return;   // 该 id 已有压缩在跑，跳过
        try {
            compactionExecutor.execute(() -> {
                try {
                    compactNow();
                } finally {
                    COMPACTING.remove(id);
                }
            });
        } catch (RejectedExecutionException rex) {
            // 池满 → 宁可同步压缩，也别让 bound 无限增长
            COMPACTING.remove(id);
            compactNow();
        }
    }

    /**
     * 真正的压缩：锁内取快照 → <strong>锁外</strong>调 LLM 摘要 → 锁内把 prefix 换成 summary。
     * 期间同会话新 add 的消息保留在尾部。
     */
    private void compactNow() {
        List<ChatMessage> snapshot;
        synchronized (lock()) {
            snapshot = new ArrayList<>(store.getMessages(id));
        }
        // 条数没超阈、且（没开 token 计量或 token 也没超）→ 无需压缩。与 add() 的触发条件对称，
        // 否则 token 触发了 triggerCompaction 却被这里按条数挡掉、永远压不动。
        boolean tokenExceeded = estimator != null && maxTokens > 0 && estimatedTokens(snapshot) > maxTokens;
        if (snapshot.size() <= threshold && !tokenExceeded) return;
        // 还得有东西可压（至少比 keepRecent 多）
        if (snapshot.size() <= keepRecent) return;

        int compactCount = snapshot.size() - keepRecent;
        List<ChatMessage> toCompact = snapshot.subList(0, compactCount);

        String existingSummary = extractExistingSummary(toCompact);
        String transcript = renderTranscript(stripSummary(toCompact));
        String prompt = """
                Concisely summarize the following conversation so a future reader can pick up
                without losing important facts, decisions, user preferences, or open questions.
                Output 5-10 short bullet points. No preamble.

                %sNEW MESSAGES:
                %s
                """.formatted(existingSummary.isEmpty() ? "" : "EXISTING SUMMARY:\n" + existingSummary + "\n\n",
                        transcript);

        String summary;
        try {
            summary = summarizer.chat(prompt).trim();   // 慢调用，不持锁
        } catch (Exception e) {
            log.warn("Memory summarization failed; leaving messages uncompacted (id={})", id, e);
            return;
        }
        // 膨胀上限兜底：多轮「旧摘要 + 新消息」反复压缩可能越滚越长，超限截断（prompt 已要求 5-10 bullet，这是确定性兜底）。
        if (maxSummaryChars > 0 && summary.length() > maxSummaryChars) {
            log.debug("summary {} chars exceeds cap {}, truncating (id={})", summary.length(), maxSummaryChars, id);
            summary = summary.substring(0, maxSummaryChars) + "…（摘要已截断）";
        }

        // 锁内合并：重读最新列表（可能在摘要期间新增了消息），把前 compactCount 条换成 summary。
        synchronized (lock()) {
            List<ChatMessage> latest = new ArrayList<>(store.getMessages(id));
            if (latest.size() < compactCount) {
                // 被 clear 或异常缩短 —— 放弃本次合并，避免错位
                return;
            }
            List<ChatMessage> tail = new ArrayList<>(latest.subList(compactCount, latest.size()));
            List<ChatMessage> result = new ArrayList<>(tail.size() + 1);
            result.add(SystemMessage.from(SUMMARY_PREFIX + summary));
            result.addAll(tail);
            store.updateMessages(id, result);
        }
        log.debug("Compacted {} messages into summary ({} chars) id={}", compactCount, summary.length(), id);
    }

    private static String extractExistingSummary(List<ChatMessage> messages) {
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage sys && sys.text().startsWith(SUMMARY_PREFIX)) {
            return sys.text().substring(SUMMARY_PREFIX.length());
        }
        return "";
    }

    private static List<ChatMessage> stripSummary(List<ChatMessage> messages) {
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage sys && sys.text().startsWith(SUMMARY_PREFIX)) {
            return messages.subList(1, messages.size());
        }
        return messages;
    }

    private static String renderTranscript(List<ChatMessage> messages) {
        return messages.stream()
                .map(SummarizingChatMemory::render)
                .collect(Collectors.joining("\n"));
    }

    private static String render(ChatMessage m) {
        if (m instanceof UserMessage u) return "USER: " + u.singleText();
        if (m instanceof AiMessage a) return "ASSISTANT: " + a.text();
        if (m instanceof SystemMessage s) return "SYSTEM: " + s.text();
        return m.getClass().getSimpleName() + ": " + m;
    }
}
