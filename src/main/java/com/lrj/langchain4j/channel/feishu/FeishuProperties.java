package com.lrj.langchain4j.channel.feishu;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 飞书渠道配置 {@code app.channel.feishu.*}（Milestone 1.B）。整套渠道由 {@code enabled} 开关（默认关），
 * 见 {@link FeishuConfig}。样板选飞书：交互卡片最全，审批 UI 零额外前端。
 *
 * <p>四类凭据来自飞书开放平台「开发者后台 → 应用 → 凭证与基础信息 / 事件订阅」：
 * <ul>
 *   <li>{@code appId}/{@code appSecret} —— 调出站 API（拿 tenant_access_token）；</li>
 *   <li>{@code verificationToken} —— 事件订阅回调里校验来源（明文模式 / 加密模式都带）；</li>
 *   <li>{@code encryptKey} —— 事件订阅「加密策略」开启后，回调 body 的 {@code encrypt} 字段的 AES 解密 key
 *       （未开加密则留空，回调是明文 JSON）。</li>
 * </ul>
 *
 * <p>{@code approverChatId}：高风险退款进人工审批时，把审批卡片推到这个群/会话（审批人在飞书点按钮回调
 * {@code complete}）。留空则不推卡片，审批人仍走 REST {@code /workflow/tasks*}。
 */
@ConfigurationProperties(prefix = "app.channel.feishu")
public class FeishuProperties {

    /** 总开关。关闭时整套飞书渠道 Bean 不装配，默认启动零影响。 */
    private boolean enabled = false;

    private String appId = "";
    private String appSecret = "";
    private String verificationToken = "";
    /** 加密策略的 AES key；留空 = 事件订阅未开加密（回调明文）。 */
    private String encryptKey = "";

    /** 飞书开放平台 base-url。自建应用国内版默认 open.feishu.cn；Lark 国际版换 open.larksuite.com。 */
    private String baseUrl = "https://open.feishu.cn";

    /**
     * 本飞书应用映射到的内部租户 id（飞书企业身份天然是租户）。v1 单租户：所有入站消息归到此租户，
     * 下游 RAG/记忆/审批按它隔离。默认 {@code tenantA} 对齐 application.yml 里 seed 的审批人 key
     * （{@code dev-key-tenantA-approver}），便于端到端 demo。多租户映射（tenant_key→tenantId）留待后续。
     */
    private String tenant = "tenantA";

    /** 高风险审批卡片推送目标会话（chat_id）。留空则不推卡片。 */
    private String approverChatId = "";

    /** 出站 HTTP 超时（毫秒）。 */
    private int httpTimeoutMs = 5000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }

    public String getVerificationToken() { return verificationToken; }
    public void setVerificationToken(String verificationToken) { this.verificationToken = verificationToken; }

    public String getEncryptKey() { return encryptKey; }
    public void setEncryptKey(String encryptKey) { this.encryptKey = encryptKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getTenant() { return tenant; }
    public void setTenant(String tenant) { this.tenant = tenant; }

    public String getApproverChatId() { return approverChatId; }
    public void setApproverChatId(String approverChatId) { this.approverChatId = approverChatId; }

    public int getHttpTimeoutMs() { return httpTimeoutMs; }
    public void setHttpTimeoutMs(int httpTimeoutMs) { this.httpTimeoutMs = httpTimeoutMs; }
}
