package com.lrj.langchain4j.a2a;

import com.lrj.langchain4j.a2a.protocol.A2aTask;
import com.lrj.langchain4j.a2a.protocol.TaskState;
import com.lrj.langchain4j.ai.multiagent.MultiAgentService;
import com.lrj.langchain4j.async.AsyncTask;
import com.lrj.langchain4j.async.TaskKind;
import com.lrj.langchain4j.async.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2aMapper 是 A2A 协议层唯一的确定性翻译（内部 TaskStatus ↔ A2A TaskState、结果摊成 artifact），
 * 对齐 CLAUDE.md「确定性逻辑走 JUnit」。
 */
class A2aMapperTest {

    private final A2aMapper mapper = new A2aMapper();

    @Test
    void taskStatus_mapsToA2aState_allEnumValues() {
        assertThat(mapper.toTaskState(TaskStatus.PENDING)).isEqualTo(TaskState.SUBMITTED);
        assertThat(mapper.toTaskState(TaskStatus.RUNNING)).isEqualTo(TaskState.WORKING);
        assertThat(mapper.toTaskState(TaskStatus.SUCCEEDED)).isEqualTo(TaskState.COMPLETED);
        assertThat(mapper.toTaskState(TaskStatus.FAILED)).isEqualTo(TaskState.FAILED);
        assertThat(mapper.toTaskState(TaskStatus.CANCELLED)).isEqualTo(TaskState.CANCELED);
    }

    @Test
    void renderResult_extractsFinalAnswerFromMultiAgentRun() {
        MultiAgentService.Run run = new MultiAgentService.Run(null, null, "the final answer", null, true);
        assertThat(mapper.renderResult(run)).isEqualTo("the final answer");
        assertThat(mapper.renderResult(null)).isNull();
        assertThat(mapper.renderResult("plain string")).isEqualTo("plain string");
    }

    @Test
    void toA2aTask_succeeded_carriesTextArtifact() {
        MultiAgentService.Run run = new MultiAgentService.Run(null, null, "answer body", null, true);
        AsyncTask task = task(TaskStatus.SUCCEEDED, run, null);

        A2aTask a2a = mapper.toA2aTask(task);

        assertThat(a2a.id()).isEqualTo("tid");
        assertThat(a2a.status().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(a2a.artifacts()).hasSize(1);
        assertThat(a2a.artifacts().get(0).parts().get(0).text()).isEqualTo("answer body");
        assertThat(a2a.kind()).isEqualTo("task");
    }

    @Test
    void toA2aTask_failed_putsErrorInStatusMessage_noArtifact() {
        AsyncTask task = task(TaskStatus.FAILED, null, "boom: timeout");

        A2aTask a2a = mapper.toA2aTask(task);

        assertThat(a2a.status().state()).isEqualTo(TaskState.FAILED);
        assertThat(a2a.status().message().textContent()).isEqualTo("boom: timeout");
        assertThat(a2a.artifacts()).isNull();
    }

    @Test
    void toA2aTask_working_hasNoArtifactNoMessage() {
        A2aTask a2a = mapper.toA2aTask(task(TaskStatus.RUNNING, null, null));
        assertThat(a2a.status().state()).isEqualTo(TaskState.WORKING);
        assertThat(a2a.artifacts()).isNull();
        assertThat(a2a.status().message()).isNull();
    }

    private static AsyncTask task(TaskStatus status, Object result, String error) {
        Instant now = Instant.now();
        return new AsyncTask("tid", "tenant", "user", TaskKind.MULTI_AGENT, status,
                Map.of("message", "q"), result, error, null, now, now, status.isTerminal() ? now : null);
    }
}
