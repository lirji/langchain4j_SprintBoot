package com.lrj.langchain4j.cost;

import java.util.Map;

/**
 * per-tenant 日 USD 成本累加器的抽象。两种后端（与 {@code app.token-budget} 同款范式）：
 * <ul>
 *   <li>{@link InMemoryCostTracker}（默认，{@code app.cost.store=in-memory}）—— 进程内、限单 JVM；</li>
 *   <li>{@link RedisCostTracker}（{@code app.cost.store=redis}）—— 共享计数落 Redis
 *       （{@code INCRBYFLOAT} 原子累加 + 次日午夜自动过期），多 pod 汇总同一份成本账。</li>
 * </ul>
 *
 * <p>成本是**可观测**指标（只累计不拦截），所以 Redis 后端主要价值是<strong>多副本成本汇总正确</strong>
 * （in-memory 各 pod 各算各的，看板要手动加总）。消费方（{@link CostChatModelListener} 回填 /
 * {@link CostEndpoint} 快照）只依赖本接口，换后端零改动。
 */
public interface CostTracker {

    /** 给当前 tenant 累加 USD；{@link CostChatModelListener#onResponse} 调。<=0 忽略。 */
    void record(String tenantId, double usd);

    /** 当前 tenant 今日累计 USD（跨日自动归零）。 */
    double currentUsd(String tenantId);

    /** Actuator 端点用：按 tenant 列出今日累计 USD 快照。 */
    Map<String, Snapshot> snapshotAll();

    record Snapshot(double usd, String currency, String day) {}
}
