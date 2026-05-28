package com.lrj.langchain4j.ai.multiagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.ai.reflexion.Critic;
import com.lrj.langchain4j.ai.reflexion.Critique;
import com.lrj.langchain4j.config.MultiAgentConfig.PlanExecuteProperties;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Multi-agent 流水线：Planner 拆任务 → 按 DAG 拓扑序分层并行执行 → Synthesizer 合成。
 *
 * <p>DAG 算法（Kahn）：
 * <ol>
 *   <li>把所有 task 按 dependsOn 入度分组，找入度 0 的为第一层</li>
 *   <li>同层并行扔 evalExecutor 跑，等本层全部完成</li>
 *   <li>把已完成 task 从下一层依赖里去掉，继续找新一批入度 0 的</li>
 *   <li>所有 task 处理完结束；中途任何一层为空 → 有环 → 降级为 flat 全并行 + log 警告</li>
 * </ol>
 *
 * <p>Worker 收到的 {@code upstream} 是 dependsOn 列表对应 task 的输出拼成的 string，
 * 没有上游时传空串。Worker 自己消化 upstream context，无需感知 DAG。
 */
@Service
public class MultiAgentService {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Planner planner;
    private final Replanner replanner;
    private final Worker worker;
    private final Synthesizer synthesizer;
    private final Critic critic;
    private final PlanExecuteProperties replanProps;
    private final Executor executor;

    public MultiAgentService(Planner planner,
                             Replanner replanner,
                             Worker worker,
                             Synthesizer synthesizer,
                             Critic critic,
                             PlanExecuteProperties replanProps,
                             @Qualifier("multiAgentExecutor") Executor executor) {
        this.planner = planner;
        this.replanner = replanner;
        this.worker = worker;
        this.synthesizer = synthesizer;
        this.critic = critic;
        this.replanProps = replanProps;
        this.executor = executor;
    }

    public record WorkerResult(String taskId, String description, String result) {}

    /**
     * 一轮 plan → execute → synthesize 的产物。replan 关闭时只有 1 个；开启时按时间序，
     * 最后一个是最终采纳的。{@code critique} 在 replan 关闭时为 null（没评分）。
     */
    public record Attempt(int n,
                          Plan plan,
                          List<WorkerResult> workerResults,
                          String finalAnswer,
                          Critique critique,
                          double aggregate) {}

    /**
     * Multi-agent 调用的完整产物。
     *
     * <p>{@code finalAnswer} = 最后一个 attempt 的 finalAnswer，方便 controller 直接取。
     * {@code acceptedByThreshold}：replan 关时恒为 true；开时表示最后一轮分数是否 ≥ threshold。
     *
     * <p>顶层 {@code plan} / {@code workerResults} 字段保留指向**最后一个 attempt**，
     * 兼容旧 eval harness 里查 "tasks: N" 字面的 case。多轮明细看 {@code attempts}。
     */
    public record Run(Plan plan,
                      List<WorkerResult> workerResults,
                      String finalAnswer,
                      List<Attempt> attempts,
                      boolean acceptedByThreshold) {}

    public Run run(String question) {
        List<Attempt> attempts = new ArrayList<>();
        Plan plan = planner.plan(question);
        log.info("planner produced {} sub-tasks", plan.tasks().size());

        Attempt first = executeAndScore(question, plan, 1);
        attempts.add(first);

        if (!replanProps.isEnabled()) {
            // 关闭 replan 时不评分（critique=null, aggregate=NaN），保持原 token 成本
            return finalize(attempts, true);
        }

        // replan 开启：以阈值为门、按 max-replans 上限循环
        int n = 1;
        Attempt last = first;
        while (last.aggregate() < replanProps.getThreshold() && n - 1 < replanProps.getMaxReplans()) {
            n++;
            log.info("attempt {} agg={} below threshold {}, asking Replanner (issue: {})",
                    n - 1, last.aggregate(), replanProps.getThreshold(),
                    last.critique() != null ? last.critique().mainIssue() : "n/a");
            Plan revised = revisePlan(question, last);
            last = executeAndScore(question, revised, n);
            attempts.add(last);
        }

        boolean accepted = last.aggregate() >= replanProps.getThreshold();
        return finalize(attempts, accepted);
    }

    /** 跑一轮：DAG 执行 → synthesize → （如果 replan 开启）critique。 */
    private Attempt executeAndScore(String question, Plan plan, int n) {
        List<WorkerResult> ordered = executeDag(plan);
        String formatted = ordered.stream()
                .map(r -> "[" + r.taskId() + "] " + r.description() + "\n→ " + r.result())
                .collect(Collectors.joining("\n\n"));
        String answer = synthesizer.synthesize(question, formatted);

        if (!replanProps.isEnabled()) {
            return new Attempt(n, plan, ordered, answer, null, Double.NaN);
        }
        Critique c = critic.critique(question, answer);
        double agg = aggregate(c);
        log.info("attempt {} critique corr={} comp={} clar={} agg={} issue={}",
                n, c.correctness(), c.completeness(), c.clarity(), agg, c.mainIssue());
        return new Attempt(n, plan, ordered, answer, c, agg);
    }

    private Plan revisePlan(String question, Attempt last) {
        String prevPlanJson;
        try {
            prevPlanJson = JSON.writeValueAsString(last.plan());
        } catch (JsonProcessingException e) {
            // 极不可能（Plan 是简单 record），兜底用 toString
            log.warn("failed to serialize previous plan, falling back to toString", e);
            prevPlanJson = last.plan().toString();
        }
        Critique c = last.critique();
        return replanner.revise(question, prevPlanJson, last.finalAnswer(),
                c.correctness(), c.completeness(), c.clarity(), c.mainIssue());
    }

    /** 把 attempts 收口成 Run：顶层字段指向最后一个 attempt（向后兼容 eval harness）。 */
    private Run finalize(List<Attempt> attempts, boolean accepted) {
        Attempt last = attempts.get(attempts.size() - 1);
        return new Run(last.plan(), last.workerResults(), last.finalAnswer(), attempts, accepted);
    }

    private List<WorkerResult> executeDag(Plan plan) {
        List<List<SubTask>> levels = topologicalLevels(plan.tasks());
        if (levels == null) {
            log.warn("cycle detected in plan, falling back to flat fan-out (deps ignored)");
            levels = List.of(plan.tasks());
        }
        Map<String, WorkerResult> byId = new ConcurrentHashMap<>();
        List<WorkerResult> ordered = new ArrayList<>(plan.tasks().size());
        for (List<SubTask> level : levels) {
            List<CompletableFuture<WorkerResult>> futures = level.stream()
                    .map(t -> CompletableFuture.supplyAsync(() -> runOne(t, byId), executor))
                    .toList();
            for (CompletableFuture<WorkerResult> f : futures) {
                WorkerResult r = f.join();
                byId.put(r.taskId(), r);
                ordered.add(r);
            }
        }
        return ordered;
    }

    /** 加权聚合 Critique 3 维分；权重总和当分母，跟 ReflexiveService 保持一致。 */
    private double aggregate(Critique c) {
        PlanExecuteProperties.Weights w = replanProps.getWeights();
        double sum = w.getCorrectness() + w.getCompleteness() + w.getClarity();
        if (sum <= 0) {
            return (c.correctness() + c.completeness() + c.clarity()) / 3.0;
        }
        return (w.getCorrectness() * c.correctness()
                + w.getCompleteness() * c.completeness()
                + w.getClarity() * c.clarity()) / sum;
    }

    /**
     * SSE 流式版本：按阶段 emit 事件。事件 names：
     * <ul>
     *   <li>{@code plan} — 全部子任务的 plan 一次性发出（含 dependsOn）</li>
     *   <li>{@code worker-result} — 每个 worker 完成时立即 emit（不等同层其他 worker）</li>
     *   <li>{@code synthesis-token} — Synthesizer 流式 token，前端可立刻渲染</li>
     *   <li>{@code done} — 全部完成，最终全文一并发一次（方便客户端收口）</li>
     *   <li>{@code error} — 任何阶段异常都 emit + completeWithError</li>
     * </ul>
     *
     * <p>Worker 仍非流式（多 worker 同时流 token 会混乱）—— 这里的核心收益是
     * Synthesizer 那 10-20s 一次性等变成 token-by-token 立刻看到。
     */
    public void runStream(String question, SseEmitter emitter) {
        try {
            Plan plan = planner.plan(question);
            log.info("planner produced {} sub-tasks (stream)", plan.tasks().size());
            emitter.send(SseEmitter.event().name("plan").data(plan));

            List<List<SubTask>> levels = topologicalLevels(plan.tasks());
            if (levels == null) {
                log.warn("cycle detected (stream), falling back to flat fan-out");
                levels = List.of(plan.tasks());
            }
            Map<String, WorkerResult> byId = new ConcurrentHashMap<>();
            List<WorkerResult> ordered = new ArrayList<>(plan.tasks().size());
            for (List<SubTask> level : levels) {
                List<CompletableFuture<WorkerResult>> futures = level.stream()
                        .map(t -> CompletableFuture.supplyAsync(() -> runOne(t, byId), executor))
                        .toList();
                for (CompletableFuture<WorkerResult> f : futures) {
                    WorkerResult r = f.join();
                    byId.put(r.taskId(), r);
                    ordered.add(r);
                    safeSend(emitter, "worker-result", r);
                }
            }

            String formatted = ordered.stream()
                    .map(r -> "[" + r.taskId() + "] " + r.description() + "\n→ " + r.result())
                    .collect(Collectors.joining("\n\n"));

            final Plan finalPlan = plan;
            final List<WorkerResult> finalOrdered = ordered;
            TokenStream tokens = synthesizer.synthesizeStream(question, formatted);
            tokens
                    .onPartialResponse(token -> safeSend(emitter, "synthesis-token", token))
                    .onCompleteResponse(resp -> {
                        // 全文一并发一次，方便客户端做最终落盘 / metric 计算。
                        // stream 暂不接 replan 闭环 —— 单 attempt + acceptedByThreshold=true，
                        // 跟 replan 关闭时的 run() 语义一致。lambda 捕获用 final 别名是为了
                        // 兼容 "effectively final" 在某些 javac 版本对集合方法调用的检查口径。
                        String text = resp.aiMessage() != null ? resp.aiMessage().text() : "";
                        Attempt only = new Attempt(1, finalPlan, finalOrdered, text, null, Double.NaN);
                        safeSend(emitter, "done", new Run(finalPlan, finalOrdered, text,
                                List.of(only), true));
                        emitter.complete();
                    })
                    .onError(err -> {
                        log.error("synthesis stream error", err);
                        safeSend(emitter, "error", err.getMessage());
                        emitter.completeWithError(err);
                    })
                    .start();
        } catch (Exception e) {
            log.error("runStream pre-synthesis error", e);
            safeSend(emitter, "error", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    private static void safeSend(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            // emitter 可能已被客户端关闭；不当作硬错，让外层逻辑继续
            // （不可恢复的话 onError / completeWithError 会兜底）
        }
    }

    private WorkerResult runOne(SubTask t, Map<String, WorkerResult> upstreamResults) {
        String upstream = buildUpstream(t.effectiveDependsOn(), upstreamResults);
        String result = worker.execute(t.description(), upstream);
        return new WorkerResult(t.id(), t.description(), result);
    }

    /** 拼成给 Worker 的 upstream 上下文；没有 deps 返回空串（模板渲染时只显示 task 部分）。 */
    private String buildUpstream(List<String> depIds, Map<String, WorkerResult> resultsById) {
        if (depIds.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Inputs from upstream tasks:\n");
        for (String depId : depIds) {
            WorkerResult r = resultsById.get(depId);
            if (r == null) {
                // 不应该发生（拓扑序保证上游先跑完），但稳妥起见跳过
                continue;
            }
            sb.append("[").append(depId).append("] ").append(r.description()).append("\n");
            sb.append("→ ").append(r.result()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Kahn 拓扑排序，按"层"分组（同层互相独立，可并行）。环检测返回 null。
     * 还会清洗掉 dependsOn 里不存在的 id，并 log 警告。
     *
     * <p>包级可见以便单元测试直接调用（{@code MultiAgentServiceTest}），不要改成 private。
     */
    List<List<SubTask>> topologicalLevels(List<SubTask> tasks) {
        if (tasks.isEmpty()) return List.of();
        Map<String, SubTask> byId = tasks.stream()
                .collect(Collectors.toMap(SubTask::id, t -> t, (a, b) -> a));
        Map<String, Set<String>> pendingDeps = new HashMap<>();
        for (SubTask t : tasks) {
            Set<String> deps = new HashSet<>(t.effectiveDependsOn());
            // 清洗未知 id
            deps.removeIf(d -> {
                if (!byId.containsKey(d)) {
                    log.warn("task {} depends on unknown id '{}', ignoring", t.id(), d);
                    return true;
                }
                return false;
            });
            pendingDeps.put(t.id(), deps);
        }

        List<List<SubTask>> levels = new ArrayList<>();
        Set<String> processed = new HashSet<>();

        while (processed.size() < tasks.size()) {
            List<SubTask> level = pendingDeps.entrySet().stream()
                    .filter(e -> !processed.contains(e.getKey()))
                    .filter(e -> processed.containsAll(e.getValue()))
                    .map(e -> byId.get(e.getKey()))
                    .toList();
            if (level.isEmpty()) {
                // 没有任何 task 可以推进 = 剩余 task 之间存在环
                return null;
            }
            levels.add(Collections.unmodifiableList(level));
            level.forEach(t -> processed.add(t.id()));
        }
        return levels;
    }
}
