package com.lrj.langchain4j.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 工作流编排（Flowable）配置。整体由 {@code app.workflow.enabled} 开关（默认关），
 * 见 {@link WorkflowConfig}。
 *
 * <p>{@link Datasource} 是 Flowable 引擎自己的 JDBC 源（建 ~25 张 {@code ACT_*} 表）。
 * 走 MySQL（持久化，能真正"挂起等审批、期间重启不丢"）。默认指向本机
 * {@code localhost:3306/flowable}，{@code createDatabaseIfNotExist=true} 首次启动自动建库。
 * 跟 NL2SQL 的只读库是两个独立 DataSource，各自手动构建、互不干扰。
 */
@ConfigurationProperties(prefix = "app.workflow")
public class WorkflowProperties {

    /** 总开关。关闭时整套 workflow Bean 不装配，默认启动零影响。 */
    private boolean enabled = false;

    private Datasource datasource = new Datasource();

    /**
     * 审批超时阈值：UserTask 挂起超过此时长，{@code ApprovalTimeoutSweeper} 把它自动驳回，
     * 避免审批人漏看导致流程永久挂起（roadmap F 节 🔴 一档 #1）。默认 24h，ISO-8601 Duration（如 {@code PT24H}）。
     */
    private Duration approvalTimeout = Duration.ofHours(24);

    /** 超时扫描间隔（毫秒）。照抄 {@code TaskStore.cleanup()} 的 @Scheduled 范式，默认 60s 一次。 */
    private long timeoutSweepIntervalMs = 60_000;

    /**
     * ServiceTask 内 LLM 调用（assess 抽工单 / resolve 生成答复）的最大尝试次数（#3 失败补偿）。
     * 含首次，默认 2 = 首次 + 1 次重试。LLM 跑挂到耗尽后<b>不向 Flowable 抛异常</b>（那会回滚已提交的人工
     * 审批决定、把任务退回 active），改为写降级兜底答复，事务照常提交——见 {@code ServiceTaskDelegates}。
     */
    private int llmMaxAttempts = 2;

    /** 上面重试之间的退避（毫秒），默认 500。设 0 关退避（单测用）。 */
    private long llmRetryBackoffMs = 500;

    /**
     * Flowable 历史实例（{@code ACT_HI_*}）+ 对应 {@code WF_REPLY} 行的保留期（#4 历史表无限增长）。
     * 已结束且早于 now−retention 的实例由 {@code WorkflowHistoryCleaner} 定期删除。审计留痕已走
     * {@code logs/audit.jsonl}，Flowable 历史只为流程态查询，可短留。ISO-8601 Duration，默认 30 天。
     */
    private Duration historyRetention = Duration.ofDays(30);

    /** 历史清理扫描间隔（毫秒），默认 1h 一次（远低频于超时扫描）。 */
    private long historyCleanupIntervalMs = 3_600_000;

    private Outbox outbox = new Outbox();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Datasource getDatasource() { return datasource; }
    public void setDatasource(Datasource datasource) { this.datasource = datasource; }

    public Duration getApprovalTimeout() { return approvalTimeout; }
    public void setApprovalTimeout(Duration approvalTimeout) { this.approvalTimeout = approvalTimeout; }

    public long getTimeoutSweepIntervalMs() { return timeoutSweepIntervalMs; }
    public void setTimeoutSweepIntervalMs(long timeoutSweepIntervalMs) { this.timeoutSweepIntervalMs = timeoutSweepIntervalMs; }

    public int getLlmMaxAttempts() { return llmMaxAttempts; }
    public void setLlmMaxAttempts(int llmMaxAttempts) { this.llmMaxAttempts = llmMaxAttempts; }

    public long getLlmRetryBackoffMs() { return llmRetryBackoffMs; }
    public void setLlmRetryBackoffMs(long llmRetryBackoffMs) { this.llmRetryBackoffMs = llmRetryBackoffMs; }

    public Duration getHistoryRetention() { return historyRetention; }
    public void setHistoryRetention(Duration historyRetention) { this.historyRetention = historyRetention; }

    public long getHistoryCleanupIntervalMs() { return historyCleanupIntervalMs; }
    public void setHistoryCleanupIntervalMs(long historyCleanupIntervalMs) { this.historyCleanupIntervalMs = historyCleanupIntervalMs; }

    public Outbox getOutbox() { return outbox; }
    public void setOutbox(Outbox outbox) { this.outbox = outbox; }

    /**
     * 终态回推可靠投递（#8 outbox）。流程到终态时若发起方传了 {@code webhookUrl}，把"待投递"持久化到
     * {@code WF_OUTBOX} 表，由 {@code WorkflowOutboxDispatcher} 定时重投——补现有 {@code WebhookDispatcher}
     * 内存重试"进程一挂就丢"的缺口。投递成功 → DELIVERED；4xx 或重试耗尽 → DEAD（DLQ，人工捞）。
     */
    public static class Outbox {
        /** 重投扫描间隔（毫秒），默认 30s。 */
        private long pollIntervalMs = 30_000;
        /** 单轮最多投递多少条（防一轮卡死），默认 50。 */
        private int batchSize = 50;
        /** 最大尝试次数（含首次），超过进 DEAD。默认 6。 */
        private int maxAttempts = 6;
        /** 指数退避基数（毫秒）：第 n 次失败后下次 = now + base*3^(n-1)。默认 5s。 */
        private long baseBackoffMs = 5_000;

        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getBaseBackoffMs() { return baseBackoffMs; }
        public void setBaseBackoffMs(long baseBackoffMs) { this.baseBackoffMs = baseBackoffMs; }
    }

    public static class Datasource {
        /** 默认本机 MySQL，首次启动自动建 flowable 库。接已有库时去掉 createDatabaseIfNotExist。 */
        private String url = "jdbc:mysql://localhost:3306/flowable?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&nullCatalogMeansCurrent=true";
        private String driverClassName = "com.mysql.cj.jdbc.Driver";
        private String username = "root";
        private String password = "";

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getDriverClassName() { return driverClassName; }
        public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
