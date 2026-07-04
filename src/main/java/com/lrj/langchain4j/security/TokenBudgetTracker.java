package com.lrj.langchain4j.security;

import java.util.Map;

/**
 * per-tenant 日 token 预算计数器的抽象。两种后端：
 * <ul>
 *   <li>{@link InMemoryTokenBudgetTracker}（默认，{@code app.token-budget.store=in-memory}）——
 *       进程内 CHM，重启即丢，<strong>限单 JVM</strong>：多副本各算各的，配额实际被放大到 N 倍。</li>
 *   <li>{@link RedisTokenBudgetTracker}（{@code app.token-budget.store=redis}）——
 *       共享计数落 Redis（Lua {@code INCRBY} 原子累加 + {@code PEXPIREAT} 次日午夜自动过期），
 *       <strong>多 pod 共享同一份配额</strong>，是这套限额在水平扩容下真正生效的前提。</li>
 * </ul>
 *
 * <p>消费方（{@link TokenBudgetGuardFilter} 预检 / {@link com.lrj.langchain4j.observability.TokenBudgetChatModelListener}
 * 回填 / {@link com.lrj.langchain4j.observability.TokenBudgetEndpoint} 快照）只依赖本接口，换后端零改动 ——
 * 这正是当初 {@code InMemory} 版注释里承诺的"业务接口保持不变"。
 */
public interface TokenBudgetTracker {

    /** 当前 tenant 今天已用 token 数（跨日自动归零：新的一天 = 新计数）。 */
    long currentUsed(String tenantId);

    /** 当前 tenant 是否已用满（>=）今日预算；请求前预检用。 */
    boolean wouldExceed(String tenantId);

    /** 给当前 tenant 累加 token；listener.onResponse 调。<=0 忽略。 */
    void consume(String tenantId, long tokens);

    /** 到次日 0 点的秒数（按配置时区）；429 响应的 Retry-After 用。 */
    long secondsUntilReset();

    /** Actuator 端点用：按 tenant 列出今日 used / budget 快照。 */
    Map<String, Snapshot> snapshotAll();

    record Snapshot(long used, long budget, String day) {}
}
