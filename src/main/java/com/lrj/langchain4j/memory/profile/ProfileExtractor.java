package com.lrj.langchain4j.memory.profile;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 从一轮对话（用户消息 + 助手回复）里抽「值得跨会话长期记住」的用户事实。走 <strong>temp=0 判官模型</strong>
 * （{@code LlmConfig.buildJudgeChatModel}），跟 {@code Critic}/{@code GraphExtractor} 同思路。
 *
 * <p>核心约束（决定记忆质量）：只抽<strong>持久</strong>的（偏好/稳定属性/反复诉求），<strong>跳过</strong>
 * 一次性/瞬时内容（"今天天气""帮我查下这个订单"）。绝大多数闲聊轮应返回空表——宁缺毋滥，
 * 记忆库塞满噪声反而拖累后续注入。few-shot 含一个反例锚定"该返回空"。
 */
public interface ProfileExtractor {

    @SystemMessage("""
            Extract durable facts ABOUT THE USER worth remembering across future sessions,
            from one conversation turn. Output third-person statements.

            KEEP (durable): stable preferences, stable attributes, recurring needs/context.
              e.g. "偏好邮件联系" / "是 Pro 套餐用户" / "负责华东区销售" / "多次咨询退款政策"
            SKIP (transient): one-off requests, this-moment questions, small talk, anything
              that won't matter next week. Most turns have NOTHING durable — return an empty list.
            Never invent. Only what the user stated or clearly implied about themselves.

            # Example 1 — durable preference stated
            USER: 以后有事直接发我邮箱就行，我不怎么看短信
            ASSISTANT: 好的，已记下，后续通过邮件联系您。
            FACTS: [ {text:"偏好通过邮件联系，不常看短信", type:"preference"} ]

            # Example 2 — COUNTER-example: transient, nothing durable
            USER: 帮我查下订单 1001 到哪了
            ASSISTANT: 订单 1001 正在派送，预计明天送达。
            FACTS: []  (一次性查询，无跨会话价值)

            # Example 3 — stable attribute implied
            USER: 我们公司是你们的企业版客户，这个功能企业版有吗？
            ASSISTANT: 有的，企业版包含该功能。
            FACTS: [ {text:"所在公司是企业版客户", type:"attribute"} ]
            """)
    @UserMessage("""
            USER: {{userMessage}}
            ASSISTANT: {{assistantReply}}

            Extract durable user facts (empty list if none).
            """)
    ExtractedMemories extract(@V("userMessage") String userMessage, @V("assistantReply") String assistantReply);
}
