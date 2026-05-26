package com.lrj.langchain4j.ai.multiagent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测 Kahn 拓扑排序的核心 path —— 这是 round j 加的算法，回归风险最大。
 * 用 {@code null} 喂依赖（Planner/Worker/Synthesizer/executor），只调 package-private 的
 * {@code topologicalLevels} 纯函数，避免拉起 Spring 上下文 / 真发 LLM 请求。
 */
class MultiAgentServiceTest {

    private final MultiAgentService svc = new MultiAgentService(null, null, null, Runnable::run);

    @Test
    void emptyTasks_returnsEmptyLevels() {
        assertThat(svc.topologicalLevels(List.of())).isEmpty();
    }

    @Test
    void flatNoDeps_singleLevelWithAllTasks() {
        var tasks = List.of(
                new SubTask("t1", "desc1", List.of()),
                new SubTask("t2", "desc2", List.of()),
                new SubTask("t3", "desc3", null)   // null dependsOn 兜底成 empty
        );
        var levels = svc.topologicalLevels(tasks);
        assertThat(levels).hasSize(1);
        assertThat(levels.get(0))
                .extracting(SubTask::id)
                .containsExactlyInAnyOrder("t1", "t2", "t3");
    }

    @Test
    void linearChain_oneTaskPerLevel_inOrder() {
        var tasks = List.of(
                new SubTask("t1", "root", List.of()),
                new SubTask("t2", "mid", List.of("t1")),
                new SubTask("t3", "leaf", List.of("t2"))
        );
        var levels = svc.topologicalLevels(tasks);
        assertThat(levels).hasSize(3);
        assertThat(levels.get(0)).extracting(SubTask::id).containsExactly("t1");
        assertThat(levels.get(1)).extracting(SubTask::id).containsExactly("t2");
        assertThat(levels.get(2)).extracting(SubTask::id).containsExactly("t3");
    }

    @Test
    void diamondDag_threeLevels_middlePairParallel() {
        var tasks = List.of(
                new SubTask("t1", "root", List.of()),
                new SubTask("t2", "mid-a", List.of("t1")),
                new SubTask("t3", "mid-b", List.of("t1")),
                new SubTask("t4", "leaf", List.of("t2", "t3"))
        );
        var levels = svc.topologicalLevels(tasks);
        assertThat(levels).hasSize(3);
        assertThat(levels.get(0)).extracting(SubTask::id).containsExactly("t1");
        assertThat(levels.get(1)).extracting(SubTask::id).containsExactlyInAnyOrder("t2", "t3");
        assertThat(levels.get(2)).extracting(SubTask::id).containsExactly("t4");
    }

    @Test
    void cycle_returnsNull_forFlatFallback() {
        var tasks = List.of(
                new SubTask("t1", "a", List.of("t2")),
                new SubTask("t2", "b", List.of("t1"))
        );
        assertThat(svc.topologicalLevels(tasks)).isNull();
    }

    @Test
    void unknownDepId_isStrippedAndContinues() {
        var tasks = List.of(
                new SubTask("t1", "a", List.of("nonexistent")),  // dep 是无效 id
                new SubTask("t2", "b", List.of("t1"))
        );
        var levels = svc.topologicalLevels(tasks);
        assertThat(levels).hasSize(2);
        assertThat(levels.get(0)).extracting(SubTask::id).containsExactly("t1");
        assertThat(levels.get(1)).extracting(SubTask::id).containsExactly("t2");
    }

    @Test
    void selfLoop_isCycle_returnsNull() {
        // 边角 case：task 依赖自己
        var tasks = List.of(new SubTask("t1", "self", List.of("t1")));
        assertThat(svc.topologicalLevels(tasks)).isNull();
    }
}
