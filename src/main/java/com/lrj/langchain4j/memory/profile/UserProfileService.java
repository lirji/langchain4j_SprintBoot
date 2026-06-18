package com.lrj.langchain4j.memory.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 长期记忆的读写编排：
 * <ul>
 *   <li>{@link #recall} —— 取该用户的画像，格式化成可注入对话上下文的文本块（供 chat 前注入）。</li>
 *   <li>{@link #observe} —— 一轮对话后，用 {@link ProfileExtractor} 抽durable 事实并合并入库。
 *       <strong>默认异步</strong>（不阻塞 chat 响应）；抽取失败被吞，不影响主流程。</li>
 * </ul>
 */
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private final UserProfileStore store;
    private final ProfileExtractor extractor;
    private final Executor executor;
    private final boolean async;
    private final int recallLimit;

    public UserProfileService(UserProfileStore store, ProfileExtractor extractor,
                              Executor executor, boolean async, int recallLimit) {
        this.store = store;
        this.extractor = extractor;
        this.executor = executor;
        this.async = async;
        this.recallLimit = Math.max(1, recallLimit);
    }

    /** 取最近 {@code recallLimit} 条记忆，渲染成 bullet 文本块；无记忆返回空串。 */
    public String recall(String tenant, String user) {
        List<MemoryItem> items = store.list(tenant, user);
        if (items.isEmpty()) return "";
        int from = Math.max(0, items.size() - recallLimit);   // 取最近 N 条
        return items.subList(from, items.size()).stream()
                .map(i -> "- " + i.text())
                .collect(Collectors.joining("\n"));
    }

    /** 观察一轮对话，抽取并合并 durable 记忆。async=true 时投后台、立即返回。 */
    public void observe(String tenant, String user, String chatId, String userMessage, String assistantReply) {
        Runnable task = () -> doObserve(tenant, user, chatId, userMessage, assistantReply);
        if (async) {
            executor.execute(task);
        } else {
            task.run();
        }
    }

    private void doObserve(String tenant, String user, String chatId, String userMessage, String assistantReply) {
        try {
            ExtractedMemories ex = extractor.extract(userMessage, assistantReply);
            if (ex == null || ex.facts() == null || ex.facts().isEmpty()) return;
            long now = System.currentTimeMillis();
            List<MemoryItem> items = ex.facts().stream()
                    .filter(f -> f != null && f.text() != null && !f.text().isBlank())
                    .map(f -> new MemoryItem(idOf(f.text()), f.text().trim(),
                            f.type() == null ? "other" : f.type().trim(), now, chatId))
                    .toList();
            int added = store.add(tenant, user, items);
            if (added > 0) {
                log.info("user profile updated tenant={} user={} +{} memories (chatId={})", tenant, user, added, chatId);
            }
        } catch (Exception e) {
            log.warn("profile extraction failed tenant={} user={}: {}", tenant, user, e.toString());
        }
    }

    public List<MemoryItem> list(String tenant, String user) {
        return store.list(tenant, user);
    }

    public int clear(String tenant, String user) {
        return store.clear(tenant, user);
    }

    /** 稳定 id：归一文本的 hash（同一事实 → 同一 id，便于跨轮去重/删除）。 */
    private static String idOf(String text) {
        return Integer.toHexString(text.trim().toLowerCase(Locale.ROOT).hashCode());
    }
}
