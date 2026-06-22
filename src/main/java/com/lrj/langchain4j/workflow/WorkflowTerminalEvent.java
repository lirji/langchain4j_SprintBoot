package com.lrj.langchain4j.workflow;

/**
 * 工作流到达终态时发布的 Spring 事件（in-process）。{@link WorkflowService} 在三个终态点
 * （低风险自动受理 / 人工 complete / 超时驳回）各发一次，供渠道层订阅做主动回推——
 * 例如 {@code FeishuReplyListener} 监听后把 reply 推回飞书用户。
 *
 * <p>跟 {@code WorkflowOutbox}（#8）正交：outbox 是给<b>外部 HTTP webhook 订阅方</b>的<b>持久可靠</b>投递；
 * 本事件是给<b>进程内渠道监听器</b>的轻量通知（飞书是我们主动调它的 API，不是它来订阅我们的 URL）。
 * 文档「复用 #7/#8 事件机制，仅多一个监听器」即指这条。
 *
 * @param instanceId 流程实例 id
 * @param tenantId   租户
 * @param chatId     会话 id；渠道来源编码在前缀（如 {@code feishu:<open_id>}），监听器据此决定是否/如何回推
 * @param outcome    终态分支：auto | granted | rejected | timeout
 * @param reply      给用户的最终答复
 */
public record WorkflowTerminalEvent(String instanceId, String tenantId, String chatId,
                                    String outcome, String reply) {
}
