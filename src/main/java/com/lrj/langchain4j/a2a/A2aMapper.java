package com.lrj.langchain4j.a2a;

import com.lrj.langchain4j.a2a.protocol.A2aMessage;
import com.lrj.langchain4j.a2a.protocol.A2aTask;
import com.lrj.langchain4j.a2a.protocol.A2aTaskStatus;
import com.lrj.langchain4j.a2a.protocol.Artifact;
import com.lrj.langchain4j.a2a.protocol.TaskState;
import com.lrj.langchain4j.ai.multiagent.MultiAgentService;
import com.lrj.langchain4j.async.AsyncTask;
import com.lrj.langchain4j.async.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内部 {@link AsyncTask} ↔ A2A {@link A2aTask} 的纯函数翻译。无状态、无副作用 —— 单测直接覆盖。
 */
@Component
public class A2aMapper {

    /** 内部状态机 → A2A 状态。INPUT_REQUIRED 本期不产出。 */
    public TaskState toTaskState(TaskStatus s) {
        return switch (s) {
            case PENDING -> TaskState.SUBMITTED;
            case RUNNING -> TaskState.WORKING;
            case SUCCEEDED -> TaskState.COMPLETED;
            case FAILED -> TaskState.FAILED;
            case CANCELLED -> TaskState.CANCELED;
        };
    }

    /**
     * {@link AsyncTask} → {@link A2aTask}。终态 SUCCEEDED 把结果摊成 text artifact；
     * FAILED 把错误信息挂到 status.message，方便客户端看到失败原因。
     */
    public A2aTask toA2aTask(AsyncTask task) {
        TaskState state = toTaskState(task.status());
        String ts = (task.updatedAt() != null ? task.updatedAt() : task.createdAt()).toString();

        A2aMessage statusMsg = null;
        if (task.status() == TaskStatus.FAILED && task.error() != null) {
            statusMsg = A2aMessage.agentText(task.error(), task.taskId(), task.taskId());
        }

        List<Artifact> artifacts = null;
        if (task.status() == TaskStatus.SUCCEEDED) {
            String text = renderResult(task.result());
            if (text != null && !text.isBlank()) {
                artifacts = List.of(Artifact.text("answer", text));
            }
        }

        return new A2aTask(
                task.taskId(),
                task.taskId(),                       // MVP：contextId 复用 taskId（无独立会话归并）
                new A2aTaskStatus(state, statusMsg, ts),
                artifacts,
                null);
    }

    /** 把业务结果摊成纯文本。multi-agent 取 finalAnswer，其它结果走 toString 兜底。 */
    public String renderResult(Object result) {
        if (result == null) return null;
        if (result instanceof MultiAgentService.Run run) {
            return run.finalAnswer();
        }
        return result.toString();
    }
}
