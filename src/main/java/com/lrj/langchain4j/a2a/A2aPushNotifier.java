package com.lrj.langchain4j.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.a2a.protocol.A2aTask;
import com.lrj.langchain4j.a2a.protocol.PushNotificationConfig;
import com.lrj.langchain4j.async.AsyncTask;
import com.lrj.langchain4j.async.TaskEvent;
import com.lrj.langchain4j.async.webhook.WebhookProperties;
import com.lrj.langchain4j.async.webhook.WebhookSigner;
import com.lrj.langchain4j.audit.AuditEventType;
import com.lrj.langchain4j.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A2A push notification 回推：监听 {@link TaskEvent}，终态时若该 task 在 {@link A2aPushNotificationStore}
 * 登记过 push 配置，就把 **A2A 格式**的 {@link A2aTask} POST 到客户端 url。
 *
 * <p>跟 {@code WebhookDispatcher} 的分工：A2A 任务不写 {@code AsyncTask.webhookUrl}，所以
 * {@code WebhookDispatcher} 跳过它们；本类只处理 push store 里有配置的 task。两条通道互不重复触发。
 *
 * <p>复用 {@code app.async.webhook.*}（HMAC secret / timeout / 重试）。{@code X-A2A-Notification-Token}
 * 带回客户端提供的 token 供其校验回调真伪。
 */
@Component
public class A2aPushNotifier {

    private static final Logger log = LoggerFactory.getLogger(A2aPushNotifier.class);

    private final A2aPushNotificationStore pushStore;
    private final A2aMapper mapper;
    private final WebhookProperties props;
    private final ObjectMapper json;
    private final AuditLogger audit;
    private final HttpClient http;

    public A2aPushNotifier(A2aPushNotificationStore pushStore,
                           A2aMapper mapper,
                           WebhookProperties props,
                           ObjectMapper json,
                           AuditLogger audit) {
        this.pushStore = pushStore;
        this.mapper = mapper;
        this.props = props;
        this.json = json;
        this.audit = audit;
        this.http = HttpClient.newBuilder().connectTimeout(props.getTimeout()).build();
    }

    @EventListener
    @Async("webhookExecutor")
    public void onTaskEvent(TaskEvent event) {
        AsyncTask task = event.task();
        if (!task.status().isTerminal()) return;
        Optional<PushNotificationConfig> cfg = pushStore.get(task.taskId());
        if (cfg.isEmpty()) return;                                  // 不是 A2A push 任务，跳过

        deliver(task, cfg.get());
        pushStore.remove(task.taskId());                            // 终态投递后清理
    }

    private void deliver(AsyncTask task, PushNotificationConfig cfg) {
        String deliveryId = UUID.randomUUID().toString();
        String body;
        try {
            A2aTask a2aTask = mapper.toA2aTask(task);
            body = json.writeValueAsString(a2aTask);
        } catch (Exception e) {
            log.warn("A2A push payload serialization failed task={}", task.taskId(), e);
            return;
        }
        String signature = WebhookSigner.sign(props.getHmacSecret(), body);

        int attempts = props.getMaxRetries() + 1;
        long backoffNanos = props.getBackoff().toNanos();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Outcome outcome = sendOnce(cfg, body, signature, deliveryId);
            if (outcome == Outcome.SUCCESS) {
                audit.record(AuditEventType.WEBHOOK_DELIVERED, Map.of(
                        "taskId", task.taskId(), "deliveryId", deliveryId,
                        "attempt", attempt, "channel", "a2a", "url", cfg.url()));
                return;
            }
            if (outcome == Outcome.CLIENT_ERROR) {
                audit.record(AuditEventType.WEBHOOK_FAILED, Map.of(
                        "taskId", task.taskId(), "deliveryId", deliveryId,
                        "reason", "client_4xx", "channel", "a2a", "url", cfg.url()));
                return;
            }
            if (attempt < attempts) {
                long sleepMs = (long) (backoffNanos * Math.pow(3, attempt - 1)) / 1_000_000L;
                try { Thread.sleep(sleepMs); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        audit.record(AuditEventType.WEBHOOK_FAILED, Map.of(
                "taskId", task.taskId(), "deliveryId", deliveryId,
                "reason", "max_retries_exceeded", "channel", "a2a", "url", cfg.url()));
        log.warn("A2A push FAILED after {} attempts task={} url={}", attempts, task.taskId(), cfg.url());
    }

    private Outcome sendOnce(PushNotificationConfig cfg, String body, String signature, String deliveryId) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.url()))
                    .timeout(props.getTimeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Webhook-Signature", signature)
                    .header("X-Webhook-Event", "a2a.task.finished")
                    .header("X-Webhook-Delivery", deliveryId);
            if (cfg.token() != null && !cfg.token().isBlank()) {
                b.header("X-A2A-Notification-Token", cfg.token());
            }
            HttpResponse<String> resp = http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) return Outcome.SUCCESS;
            if (code >= 400 && code < 500) return Outcome.CLIENT_ERROR;
            return Outcome.SERVER_ERROR;
        } catch (Exception e) {
            log.debug("A2A push attempt failed url={}: {}", cfg.url(), e.toString());
            return Outcome.NETWORK_ERROR;
        }
    }

    private enum Outcome { SUCCESS, CLIENT_ERROR, SERVER_ERROR, NETWORK_ERROR }
}
