package com.lrj.langchain4j.cost;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.Map;

/**
 * Actuator 端点：{@code GET /actuator/cost}。返回每个 tenant 当日累计 USD 成本快照。
 * 跟 {@code /actuator/tokenbudget}（token 用量）配套 —— 一个看烧了多少 token、一个看烧了多少钱。
 *
 * <p>暴露需要在 yml 把 {@code cost} 加进 {@code management.endpoints.web.exposure.include}
 * （已随 {@code app.cost} 配置块一起给出示例）。
 */
@Endpoint(id = "cost")
public class CostEndpoint {

    private final CostTracker tracker;

    public CostEndpoint(CostTracker tracker) {
        this.tracker = tracker;
    }

    @ReadOperation
    public Map<String, CostTracker.Snapshot> snapshot() {
        return tracker.snapshotAll();
    }
}
