package com.lrj.langchain4j.observability;

import com.lrj.langchain4j.security.TokenBudgetTracker;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Actuator 端点：{@code GET /actuator/tokenbudget}。返回每个 tenant 当日 token 用量快照。
 * 运维 / dashboard 可以拉这个看哪个租户在烧钱、是否快爆配额。
 *
 * <p>暴露需要在 yml 把 {@code tokenbudget} 加进 {@code management.endpoints.web.exposure.include}。
 */
@Component
@Endpoint(id = "tokenbudget")
public class TokenBudgetEndpoint {

    private final TokenBudgetTracker tracker;

    public TokenBudgetEndpoint(TokenBudgetTracker tracker) {
        this.tracker = tracker;
    }

    @ReadOperation
    public Map<String, TokenBudgetTracker.Snapshot> snapshot() {
        return tracker.snapshotAll();
    }
}
