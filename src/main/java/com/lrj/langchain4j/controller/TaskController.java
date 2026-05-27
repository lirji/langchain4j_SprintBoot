package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.async.AsyncTask;
import com.lrj.langchain4j.async.AsyncTaskService;
import com.lrj.langchain4j.async.sse.TaskSseService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 异步任务查询 / 取消。提交入口在 {@code ChatController}（{@code POST /chat/multi-agent/async}）—— 那里
 * 业务 input 更自然；这里只暴露查询/列出/取消等 management 操作。
 *
 * <p>per-tenant 隔离在 {@link AsyncTaskService} 内部校验：跨租户访问 taskId 返回 404，跟
 * "不存在"行为一致（防止枚举攻击）。
 */
@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final AsyncTaskService tasks;
    private final TaskSseService sse;

    public TaskController(AsyncTaskService tasks, TaskSseService sse) {
        this.tasks = tasks;
        this.sse = sse;
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<AsyncTask> get(@PathVariable String taskId) {
        Optional<AsyncTask> t = tasks.get(taskId);
        return t.<ResponseEntity<AsyncTask>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<AsyncTask> listMine() {
        return tasks.listMine();
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String taskId) {
        boolean cancelled = tasks.cancel(taskId);
        if (!cancelled) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("taskId", taskId, "cancelled", true));
    }

    /**
     * SSE 推送 task 状态变更。客户端建立长连接后立刻拿到当前 snapshot，之后每次状态变更
     * 都收到一个事件，event name = 当前 status（PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED）。
     * terminal 状态后服务端主动 complete()。
     *
     * <p>跨租户访问 → 404 同 GET。
     */
    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId) {
        Optional<SseEmitter> emitter = sse.subscribe(taskId);
        if (emitter.isEmpty()) {
            SseEmitter e = new SseEmitter(0L);
            e.completeWithError(new IllegalArgumentException("task not found: " + taskId));
            return e;
        }
        return emitter.get();
    }
}
