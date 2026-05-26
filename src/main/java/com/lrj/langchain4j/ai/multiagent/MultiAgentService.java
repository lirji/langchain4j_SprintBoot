package com.lrj.langchain4j.ai.multiagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

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

    private final Planner planner;
    private final Worker worker;
    private final Synthesizer synthesizer;
    private final Executor executor;

    public MultiAgentService(Planner planner,
                             Worker worker,
                             Synthesizer synthesizer,
                             @Qualifier("multiAgentExecutor") Executor executor) {
        this.planner = planner;
        this.worker = worker;
        this.synthesizer = synthesizer;
        this.executor = executor;
    }

    public record WorkerResult(String taskId, String description, String result) {}

    public record Run(Plan plan, List<WorkerResult> workerResults, String finalAnswer) {}

    public Run run(String question) {
        Plan plan = planner.plan(question);
        log.info("planner produced {} sub-tasks", plan.tasks().size());

        List<List<SubTask>> levels = topologicalLevels(plan.tasks());
        if (levels == null) {
            log.warn("cycle detected in plan, falling back to flat fan-out (deps ignored)");
            levels = List.of(plan.tasks());
        }

        Map<String, WorkerResult> byId = new ConcurrentHashMap<>();
        List<WorkerResult> ordered = new ArrayList<>(plan.tasks().size());

        for (List<SubTask> level : levels) {
            List<CompletableFuture<WorkerResult>> futures = level.stream()
                    .map(t -> CompletableFuture.supplyAsync(
                            () -> runOne(t, byId), executor))
                    .toList();
            for (CompletableFuture<WorkerResult> f : futures) {
                WorkerResult r = f.join();
                byId.put(r.taskId(), r);
                ordered.add(r);
            }
        }

        String formatted = ordered.stream()
                .map(r -> "[" + r.taskId() + "] " + r.description() + "\n→ " + r.result())
                .collect(Collectors.joining("\n\n"));

        String finalAnswer = synthesizer.synthesize(question, formatted);
        return new Run(plan, ordered, finalAnswer);
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
