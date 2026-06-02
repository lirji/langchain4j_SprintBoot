package com.lrj.langchain4j.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * 终态回推 outbox 的持久化存储（#8）。建在 Flowable 同一个 {@code workflowDataSource} 上的
 * {@code WF_OUTBOX} 表（启动自动 DDL）。每个流程实例终态最多一条待投递（{@code INSTANCE_ID} 主键）。
 *
 * <p><b>为什么要 outbox</b>：现有 {@link com.lrj.langchain4j.async.webhook.WebhookDispatcher} 是
 * 内存里 {@code Thread.sleep} 重试，进程一挂、重试中的投递就<b>永久丢失</b>，用户干等。把"待投递"
 * 落库 + 调度线程重投，进程重启后继续投，直到 DELIVERED 或进 DEAD（DLQ）。
 *
 * <p>{@code enqueue} 在流程终态时由 {@link WorkflowService} 调用（best-effort 落库；落库后由
 * {@link WorkflowOutboxDispatcher} 保证重投）。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowOutbox {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOutbox.class);

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_DEAD = "DEAD";

    private final JdbcTemplate jdbc;

    public WorkflowOutbox(DataSource workflowDataSource) {
        this.jdbc = new JdbcTemplate(workflowDataSource);
        init();
    }

    private void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS WF_OUTBOX (
                  INSTANCE_ID VARCHAR(64) NOT NULL PRIMARY KEY,
                  TENANT_ID VARCHAR(64),
                  TARGET_URL VARCHAR(1024) NOT NULL,
                  STATUS VARCHAR(16) NOT NULL,
                  ATTEMPTS INT NOT NULL DEFAULT 0,
                  NEXT_ATTEMPT_AT BIGINT NOT NULL,
                  LAST_ERROR VARCHAR(512),
                  CREATED_AT BIGINT NOT NULL,
                  UPDATED_AT BIGINT NOT NULL,
                  INDEX IDX_WF_OUTBOX_DUE (STATUS, NEXT_ATTEMPT_AT)
                )""");
        log.info("WF_OUTBOX 表就绪（终态回推可靠投递，#8）");
    }

    /** 入队（或重置）一条待投递：status=PENDING、attempts=0、立即可投。 */
    public void enqueue(String instanceId, String tenantId, String targetUrl, long now) {
        jdbc.update("""
                INSERT INTO WF_OUTBOX (INSTANCE_ID, TENANT_ID, TARGET_URL, STATUS, ATTEMPTS, NEXT_ATTEMPT_AT, LAST_ERROR, CREATED_AT, UPDATED_AT)
                VALUES (?, ?, ?, 'PENDING', 0, ?, NULL, ?, ?)
                ON DUPLICATE KEY UPDATE TARGET_URL=VALUES(TARGET_URL), STATUS='PENDING', ATTEMPTS=0,
                  NEXT_ATTEMPT_AT=VALUES(NEXT_ATTEMPT_AT), LAST_ERROR=NULL, UPDATED_AT=VALUES(UPDATED_AT)""",
                instanceId, tenantId, targetUrl, now, now, now);
    }

    /** 取到期的待投递（STATUS=PENDING 且 NEXT_ATTEMPT_AT<=now），按到期时间升序，最多 limit 条。 */
    public List<Row> claimDue(long now, int limit) {
        return jdbc.query("""
                SELECT INSTANCE_ID, TENANT_ID, TARGET_URL, ATTEMPTS FROM WF_OUTBOX
                WHERE STATUS='PENDING' AND NEXT_ATTEMPT_AT <= ?
                ORDER BY NEXT_ATTEMPT_AT ASC LIMIT ?""",
                (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                now, limit);
    }

    public void markDelivered(String instanceId, long now) {
        jdbc.update("UPDATE WF_OUTBOX SET STATUS='DELIVERED', UPDATED_AT=? WHERE INSTANCE_ID=?", now, instanceId);
    }

    public void markRetry(String instanceId, int attempts, long nextAttemptAt, String lastError, long now) {
        jdbc.update("UPDATE WF_OUTBOX SET ATTEMPTS=?, NEXT_ATTEMPT_AT=?, LAST_ERROR=?, UPDATED_AT=? WHERE INSTANCE_ID=?",
                attempts, nextAttemptAt, trunc(lastError), now, instanceId);
    }

    public void markDead(String instanceId, int attempts, String lastError, long now) {
        jdbc.update("UPDATE WF_OUTBOX SET STATUS='DEAD', ATTEMPTS=?, LAST_ERROR=?, UPDATED_AT=? WHERE INSTANCE_ID=?",
                attempts, trunc(lastError), now, instanceId);
    }

    /** 删除某实例的 outbox 行（PII 清除 #10 调用）。 */
    public void delete(String instanceId) {
        jdbc.update("DELETE FROM WF_OUTBOX WHERE INSTANCE_ID=?", instanceId);
    }

    /**
     * 重投调度决策（纯函数，便于单测）。一次失败后：达到/超过 maxAttempts → 进 DEAD（DLQ）；
     * 否则按指数退避算下次时间 {@code now + base*3^(attemptsAfter-1)}。
     *
     * @param attemptsAfter 本次失败后的累计尝试数（已 +1）
     */
    static Decision schedule(int attemptsAfter, int maxAttempts, long now, long baseBackoffMs) {
        if (attemptsAfter >= maxAttempts) {
            return new Decision(true, 0L);
        }
        long delay = (long) (baseBackoffMs * Math.pow(3, Math.max(0, attemptsAfter - 1)));
        return new Decision(false, now + delay);
    }

    private static String trunc(String s) {
        if (s == null) return null;
        return s.length() <= 512 ? s : s.substring(0, 512);
    }

    /** 待投递行（投递所需的最小字段）。 */
    public record Row(String instanceId, String tenantId, String targetUrl, int attempts) {}

    /** schedule 的结果：是否进 DLQ + 下次重投时间。 */
    record Decision(boolean dead, long nextAttemptAt) {}
}
