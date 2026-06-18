package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.ai.agent.DeepAgentService;
import com.lrj.langchain4j.async.AsyncTask;
import com.lrj.langchain4j.async.AsyncTaskService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 深度 Agent 入口（{@code app.deep-agent.enabled=true} 才映射）。走 {@code /chat} 同套鉴权链
 * （{@code X-Api-Key} + 多租户 + 限流 + 配额）。
 *
 * <p>同步返回整条 trace（goal / steps / finalAnswer / stopReason）。一次 run 可能多次 LLM 调用、
 * 较慢，但受 {@code maxSteps} 硬预算约束；长目标的异步化（投 {@code async} 引擎）= 未来项。
 */
@RestController
@ConditionalOnProperty(name = "app.deep-agent.enabled", havingValue = "true")
public class AgentController {

    private final DeepAgentService agent;
    private final AsyncTaskService asyncTasks;

    public AgentController(DeepAgentService agent, AsyncTaskService asyncTasks) {
        this.agent = agent;
        this.asyncTasks = asyncTasks;
    }

    /** 同步：body {@code {"goal":"..."}} → 返回 {@link DeepAgentService.Run}。短目标用。 */
    @PostMapping("/agent/run")
    public ResponseEntity<?> run(@RequestBody Map<String, String> body) {
        String goal = body.getOrDefault("goal", "");
        if (goal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "goal is required"));
        }
        return ResponseEntity.ok(agent.run(goal));
    }

    /**
     * 异步：立即返回 {@link AsyncTask}（PENDING + taskId），循环投后台 {@code multiAgentExecutor}。
     * 长目标多步 LLM 调用易超时，走这个。取结果三选一：{@code GET /tasks/{id}} 轮询 /
     * {@code GET /tasks/{id}/stream} SSE / body 传 {@code webhookUrl} 终态回推（同 multi-agent async）。
     * body {@code {"goal":"...","webhookUrl":"https://..."}}（webhookUrl 可选）。
     */
    @PostMapping("/agent/run/async")
    public ResponseEntity<?> runAsync(@RequestBody Map<String, String> body) {
        String goal = body.getOrDefault("goal", "");
        if (goal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "goal is required"));
        }
        return ResponseEntity.ok(asyncTasks.submitDeepAgent(goal, body.get("webhookUrl")));
    }
}
