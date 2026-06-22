package com.lrj.langchain4j.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.async.webhook.WebhookProperties;
import com.lrj.langchain4j.async.webhook.WebhookSigner;
import com.lrj.langchain4j.audit.AuditEventType;
import com.lrj.langchain4j.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 终态回推 outbox 的重投调度器（#8）。定时扫 {@link WorkflowOutbox} 里到期的 PENDING 行，逐条 HTTP 投递：
 * <ul>
 *   <li>2xx → {@code DELIVERED}（投递成功，落库后即便重启也不会重复投）；</li>
 *   <li>4xx → 直接 {@code DEAD}（客户端拒收，再投也没用，进 DLQ 等人工）；</li>
 *   <li>5xx / 网络错误 → {@link WorkflowOutbox#schedule} 算下次重投时间，累计尝试到
 *       {@code app.workflow.outbox.max-attempts} 仍失败 → {@code DEAD}（DLQ）。</li>
 * </ul>
 *
 * <p>跟 {@code WebhookDispatcher} 的区别 = <b>可靠性</b>：那个是内存 {@code Thread.sleep} 重试，进程一挂就丢；
 * 这个把待投递落库，重投状态机驱动在调度线程，重启后从库里接着投。签名复用 {@link WebhookSigner}、
 * secret/timeout 复用 {@link WebhookProperties}（与 async webhook 同一套）。
 *
 * <p>payload = {@code {instanceId, status, reply, tenantId}}，reply 从 {@link WorkflowReplyStore} 取。
 * 调度线程不过过滤器链，无租户上下文——投递只读已落库数据、不调 LLM，无需重建 {@code TenantContext}。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOutboxDispatcher.class);

    private final WorkflowOutbox outbox;
    private final WorkflowReplyStore replyStore;
    private final WebhookProperties webhookProps;
    private final WorkflowProperties props;
    private final AuditLogger audit;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public WorkflowOutboxDispatcher(WorkflowOutbox outbox,
                                    WorkflowReplyStore replyStore,
                                    WebhookProperties webhookProps,
                                    WorkflowProperties props,
                                    AuditLogger audit,
                                    ObjectMapper mapper) {
        this.outbox = outbox;
        this.replyStore = replyStore;
        this.webhookProps = webhookProps;
        this.props = props;
        this.audit = audit;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(webhookProps.getTimeout()).build();
    }

    @Scheduled(fixedDelayString = "${app.workflow.outbox.poll-interval-ms:30000}", initialDelay = 30_000)
    public void dispatch() {
        long now = System.currentTimeMillis();
        WorkflowProperties.Outbox cfg = props.getOutbox();
        List<WorkflowOutbox.Row> due = outbox.claimDue(now, cfg.getBatchSize());
        if (due.isEmpty()) {
            return;
        }
        int delivered = 0, dead = 0, retried = 0;
        for (WorkflowOutbox.Row row : due) {
            try {
                Outcome o = deliverOne(row, cfg, now);
                switch (o) {
                    case DELIVERED -> delivered++;
                    case DEAD -> dead++;
                    case RETRY -> retried++;
                }
            } catch (Exception e) {
                // 单条异常不阻断整轮（下轮还会再扫到 PENDING）
                log.warn("outbox dispatch: 实例 {} 投递异常：{}", row.instanceId(), e.toString());
            }
        }
        log.info("outbox dispatch: 到期 {} 条 → delivered={} retry={} dead={}", due.size(), delivered, retried, dead);
    }

    private Outcome deliverOne(WorkflowOutbox.Row row, WorkflowProperties.Outbox cfg, long now) {
        String body = payload(row);
        DeliveryResult r = send(row.targetUrl(), body);
        if (r == DeliveryResult.SUCCESS) {
            outbox.markDelivered(row.instanceId(), now);
            audit.record(AuditEventType.WORKFLOW_PUSH_DELIVERED, Map.of(
                    "instanceId", row.instanceId(), "attempts", row.attempts() + 1));
            return Outcome.DELIVERED;
        }
        int attemptsAfter = row.attempts() + 1;
        if (r == DeliveryResult.CLIENT_ERROR) {
            outbox.markDead(row.instanceId(), attemptsAfter, "client_4xx", now);
            audit.record(AuditEventType.WORKFLOW_PUSH_DEAD, Map.of(
                    "instanceId", row.instanceId(), "attempts", attemptsAfter, "reason", "client_4xx"));
            return Outcome.DEAD;
        }
        WorkflowOutbox.Decision d = WorkflowOutbox.schedule(attemptsAfter, cfg.getMaxAttempts(), now, cfg.getBaseBackoffMs());
        if (d.dead()) {
            outbox.markDead(row.instanceId(), attemptsAfter, r.name(), now);
            audit.record(AuditEventType.WORKFLOW_PUSH_DEAD, Map.of(
                    "instanceId", row.instanceId(), "attempts", attemptsAfter, "reason", "max_attempts"));
            return Outcome.DEAD;
        }
        outbox.markRetry(row.instanceId(), attemptsAfter, d.nextAttemptAt(), r.name(), now);
        audit.record(AuditEventType.WORKFLOW_PUSH_FAILED, Map.of(
                "instanceId", row.instanceId(), "attempts", attemptsAfter, "reason", r.name()));
        return Outcome.RETRY;
    }

    private String payload(WorkflowOutbox.Row row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", row.instanceId());
        m.put("tenantId", row.tenantId());
        m.put("status", WorkflowService.STATUS_COMPLETED);
        m.put("reply", replyStore.find(row.instanceId()));
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"instanceId\":\"" + row.instanceId() + "\"}";
        }
    }

    private DeliveryResult send(String url, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(webhookProps.getTimeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Webhook-Signature", WebhookSigner.sign(webhookProps.getHmacSecret(), body))
                    .header("X-Webhook-Event", "workflow.completed")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            int code = http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
            if (code >= 200 && code < 300) return DeliveryResult.SUCCESS;
            if (code >= 400 && code < 500) return DeliveryResult.CLIENT_ERROR;
            return DeliveryResult.SERVER_ERROR;
        } catch (Exception e) {
            return DeliveryResult.NETWORK_ERROR;
        }
    }

    private enum DeliveryResult { SUCCESS, CLIENT_ERROR, SERVER_ERROR, NETWORK_ERROR }

    private enum Outcome { DELIVERED, DEAD, RETRY }
}
