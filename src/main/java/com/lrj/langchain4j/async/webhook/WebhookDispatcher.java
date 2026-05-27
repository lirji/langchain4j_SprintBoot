package com.lrj.langchain4j.async.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.async.AsyncTask;
import com.lrj.langchain4j.async.TaskEvent;
import com.lrj.langchain4j.audit.AuditEventType;
import com.lrj.langchain4j.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 监听 {@link TaskEvent}，在 terminal 状态时给客户端 webhookUrl 回调。
 *
 * <p>设计要点：
 * <ol>
 *   <li>只在 terminal 状态（SUCCEEDED / FAILED / CANCELLED）回调一次 —— 减少噪音</li>
 *   <li>{@code @Async("webhookExecutor")} 在独立线程池跑，不阻塞 task 主线程</li>
 *   <li>指数退避重试：{@code backoff * 1, *3, *9 ...}（默认 1s/3s/9s 共 3 次）</li>
 *   <li>4xx 不重试（客户端拒收是它的 bug，再发也没用）；5xx / 网络错误才重试</li>
 *   <li>HMAC-SHA256 签名放 {@code X-Webhook-Signature} header，客户端验签防伪造</li>
 *   <li>delivery 结果记 audit，失败可追溯</li>
 * </ol>
 *
 * <p>{@code HttpClient} 用 JDK 内置的（项目已经显式选 JdkHttpClientBuilderFactory 当 LangChain4j HTTP），
 * 不引入新依赖。
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookProperties props;
    private final ObjectMapper mapper;
    private final AuditLogger audit;
    private final HttpClient http;

    public WebhookDispatcher(WebhookProperties props, ObjectMapper mapper, AuditLogger audit) {
        this.props = props;
        this.mapper = mapper;
        this.audit = audit;
        this.http = HttpClient.newBuilder()
                .connectTimeout(props.getTimeout())
                .build();
    }

    /** 监听 terminal 事件。非终态 event 直接忽略。 */
    @EventListener
    @Async("webhookExecutor")
    public void onTaskEvent(TaskEvent event) {
        if (!props.isEnabled()) return;
        AsyncTask task = event.task();
        if (!task.status().isTerminal()) return;
        if (task.webhookUrl() == null || task.webhookUrl().isBlank()) return;

        deliver(task);
    }

    private void deliver(AsyncTask task) {
        String deliveryId = UUID.randomUUID().toString();
        String body;
        try {
            body = mapper.writeValueAsString(task);
        } catch (Exception e) {
            log.warn("webhook payload serialization failed task={}", task.taskId(), e);
            audit.record(AuditEventType.WEBHOOK_FAILED, Map.of(
                    "taskId", task.taskId(), "deliveryId", deliveryId,
                    "reason", "serialize_failed"));
            return;
        }
        String signature = WebhookSigner.sign(props.getHmacSecret(), body);

        int attempts = props.getMaxRetries() + 1;                       // 首次 + 重试
        long backoffNanos = props.getBackoff().toNanos();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            DeliveryOutcome outcome = sendOnce(task, body, signature, deliveryId);
            if (outcome == DeliveryOutcome.SUCCESS) {
                audit.record(AuditEventType.WEBHOOK_DELIVERED, Map.of(
                        "taskId", task.taskId(), "deliveryId", deliveryId,
                        "attempt", attempt, "url", task.webhookUrl()));
                return;
            }
            if (outcome == DeliveryOutcome.CLIENT_ERROR) {
                // 4xx：客户端拒收，再试只是浪费
                audit.record(AuditEventType.WEBHOOK_FAILED, Map.of(
                        "taskId", task.taskId(), "deliveryId", deliveryId,
                        "attempt", attempt, "reason", "client_4xx", "url", task.webhookUrl()));
                return;
            }
            if (attempt < attempts) {
                long sleep = (long) (backoffNanos * Math.pow(3, attempt - 1));
                try { Thread.sleep(sleep / 1_000_000L); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        // 所有 attempts 用尽
        audit.record(AuditEventType.WEBHOOK_FAILED, Map.of(
                "taskId", task.taskId(), "deliveryId", deliveryId,
                "attempts", attempts, "reason", "max_retries_exceeded", "url", task.webhookUrl()));
        log.warn("webhook delivery FAILED after {} attempts task={} url={}",
                attempts, task.taskId(), task.webhookUrl());
    }

    private DeliveryOutcome sendOnce(AsyncTask task, String body, String signature, String deliveryId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(task.webhookUrl()))
                    .timeout(props.getTimeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Webhook-Signature", signature)
                    .header("X-Webhook-Event", "task.finished")
                    .header("X-Webhook-Delivery", deliveryId)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) return DeliveryOutcome.SUCCESS;
            if (code >= 400 && code < 500) return DeliveryOutcome.CLIENT_ERROR;
            return DeliveryOutcome.SERVER_ERROR;
        } catch (Exception e) {
            log.debug("webhook attempt failed task={} url={}: {}",
                    task.taskId(), task.webhookUrl(), e.toString());
            return DeliveryOutcome.NETWORK_ERROR;
        }
    }

    private enum DeliveryOutcome { SUCCESS, CLIENT_ERROR, SERVER_ERROR, NETWORK_ERROR }
}
