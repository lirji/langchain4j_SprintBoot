package com.lrj.langchain4j.memory.profile;

import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import com.lrj.langchain4j.security.TenantContext;

/**
 * 记忆增强对话：chat 前把该用户的长期记忆<strong>召回并注入</strong>上下文，chat 后<strong>异步观察</strong>
 * 本轮更新画像。包装 {@code Assistant.chat}（跟 {@code CategoryChatService} 同范式），不改主 Assistant。
 *
 * <p>注入走"在用户消息前缀一段画像上下文"——简单、对 guardrail/RAG 透明（它们照常处理拼接后的消息）。
 * 观察用<strong>原始</strong>用户消息（不含注入前缀），让抽取看到干净输入。
 */
public class UserProfileChatService {

    private final Assistant assistant;
    private final ResolvedAssistantStyle style;
    private final UserProfileService profileService;

    public UserProfileChatService(Assistant assistant, ResolvedAssistantStyle style, UserProfileService profileService) {
        this.assistant = assistant;
        this.style = style;
        this.profileService = profileService;
    }

    public String chat(String rawChatId, String message) {
        String tenant = TenantContext.current().tenantId();
        String user = TenantContext.current().userId();
        String scopedChatId = tenant + ":" + rawChatId;

        String profile = profileService.recall(tenant, user);
        String augmented = profile.isBlank() ? message
                : "【关于该用户的长期记忆（跨会话背景，相关才用，无关请忽略）】\n" + profile
                        + "\n\n【本轮用户消息】\n" + message;

        String reply = assistant.chat(scopedChatId,
                style.getLanguage(), style.getTone(), style.getCitationPolicy(), style.getExtra(),
                augmented);

        // 用原始 message（非注入版）观察，异步更新画像
        profileService.observe(tenant, user, rawChatId, message, reply);
        return reply;
    }
}
