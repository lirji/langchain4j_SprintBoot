package com.lrj.langchain4j.async.sse;

import com.lrj.langchain4j.async.AsyncTask;
import com.lrj.langchain4j.async.AsyncTaskService;
import com.lrj.langchain4j.async.TaskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-Sent Events 推送：客户端 {@code GET /tasks/{id}/stream}，长连接拿到每次状态变更。
 *
 * <p>跟 webhook 互补：
 * <ul>
 *   <li>SSE — 同进程长连接，适合浏览器/CLI；客户端在线就能拿，离线就丢</li>
 *   <li>Webhook — server-to-server，外部 URL，自带重试，可靠投递</li>
 * </ul>
 *
 * <p>per-tenant 校验在 {@link #subscribe} 入口，跨租户访问返回 empty → controller 转 404。
 *
 * <p>**避免漏推**：注册 emitter 后立刻 send 一次当前 snapshot —— 如果 task 已经 terminal，
 * 客户端立即拿到结果 + done 事件，不需要等下一次 publishEvent。
 */
@Service
public class TaskSseService {

    private static final Logger log = LoggerFactory.getLogger(TaskSseService.class);

    /** SSE timeout：30 分钟。multi-agent 一般 10–20s 内结束，给足缓冲。 */
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final AsyncTaskService taskService;

    /** taskId -> active emitters。一个 task 可能被多个客户端 watch（少见但允许）。 */
    private final ConcurrentMap<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public TaskSseService(AsyncTaskService taskService) {
        this.taskService = taskService;
    }

    /** 控制器入口。返回 empty → controller 404（任务不存在或跨租户）。 */
    public Optional<SseEmitter> subscribe(String taskId) {
        Optional<AsyncTask> task = taskService.get(taskId);
        if (task.isEmpty()) return Optional.empty();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(t -> remove(taskId, emitter));

        // 立刻推一次当前 snapshot —— 避免"订阅时 task 已 terminal 但事件已经发完"漏推
        send(emitter, task.get());
        if (task.get().status().isTerminal()) {
            try { emitter.complete(); } catch (Exception ignored) {}
        }

        return Optional.of(emitter);
    }

    /** Spring event 同步触发。emit 失败的 emitter 在自己的 onError 回调里清掉，不在这里 catch 然后乱删。 */
    @EventListener
    public void onTaskEvent(TaskEvent event) {
        AsyncTask task = event.task();
        List<SseEmitter> list = emitters.get(task.taskId());
        if (list == null || list.isEmpty()) return;
        for (SseEmitter e : list) {
            send(e, task);
            if (task.status().isTerminal()) {
                try { e.complete(); } catch (Exception ignored) {}
            }
        }
    }

    private void send(SseEmitter emitter, AsyncTask task) {
        try {
            emitter.send(SseEmitter.event()
                    .name(task.status().name())
                    .data(task));
        } catch (IOException e) {
            // 连接已断 / 客户端关了；emitter 自己的回调会清理 list
            log.debug("sse send failed task={}: {}", task.taskId(), e.toString());
        }
    }

    private void remove(String taskId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) emitters.remove(taskId, list);
        }
    }
}
