package com.lrj.langchain4j.ai.multiagent;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * 计划里的一个子任务。{@code dependsOn} 让 Planner 能表达 DAG（任务图）—— 默认空列表（无依赖、可并行）；
 * 只有当 sub-task 的指令字面引用另一个 sub-task 的输出时才填，例如「基于 t1 列出的特性挑一个详细展开」。
 *
 * <p>{@link MultiAgentService} 按拓扑序分层执行：同层并行，跨层等待上一层。
 * 环检测到会降级为 flat 全并行 + log 警告。
 */
public record SubTask(
        @Description("Short stable id like t1, t2, t3 — used for referencing in dependsOn and results")
        String id,

        @Description("Self-contained instruction for a worker. If this task has upstream dependencies, the worker will see their outputs prepended as context.")
        String description,

        @Description("""
                IDs of upstream tasks whose outputs THIS task needs as context.
                Empty list (default) = independent, runs in parallel with other independent tasks.
                ONLY use when this task's instruction literally references another task's output
                (e.g., "based on t1's findings, ..."). Do NOT use just because tasks are
                semantically related.
                """)
        List<String> dependsOn
) {
    /** null-safe accessor —— 老的 case JSON 没这个字段时返回空列表。 */
    public List<String> effectiveDependsOn() {
        return dependsOn == null ? List.of() : dependsOn;
    }
}
