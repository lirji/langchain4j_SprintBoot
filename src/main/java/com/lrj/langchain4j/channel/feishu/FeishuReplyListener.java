package com.lrj.langchain4j.channel.feishu;

import com.lrj.langchain4j.workflow.WorkflowTerminalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 监听 {@link WorkflowTerminalEvent}，把工作流终态答复主动回推飞书用户（M1.B 渠道出站闭环）。
 *
 * <p>只处理来源是飞书的会话（{@code chatId} 以 {@code feishu:} 前缀编码 open_id）；其余渠道/REST 直发的
 * 工作流忽略。{@code @Async("feishuExecutor")} 在渠道线程池跑，不阻塞审批人 {@code complete} 的请求线程，
 * 也不阻塞超时 sweeper 调度线程。
 *
 * <p>这就是文档「复用 #7/#8 事件机制，仅多一个监听器」落地的那个监听器——审批通过/驳回/超时驳回的
 * 最终结果都经此回到用户飞书对话。低风险自动受理那条也走这里（terminal=auto）。
 */
@Component
@ConditionalOnProperty(name = "app.channel.feishu.enabled", havingValue = "true")
public class FeishuReplyListener {

    private static final Logger log = LoggerFactory.getLogger(FeishuReplyListener.class);

    static final String FEISHU_PREFIX = "feishu:";

    private final FeishuClient client;

    public FeishuReplyListener(FeishuClient client) {
        this.client = client;
    }

    @EventListener
    @Async("feishuExecutor")
    public void onWorkflowTerminal(WorkflowTerminalEvent e) {
        String chatId = e.chatId();
        if (chatId == null || !chatId.startsWith(FEISHU_PREFIX)) {
            return; // 非飞书来源，跳过
        }
        if (e.reply() == null || e.reply().isBlank()) {
            return;
        }
        String openId = chatId.substring(FEISHU_PREFIX.length());
        boolean ok = client.sendText(openId, e.reply());
        log.info("飞书回推工作流结果 instanceId={} outcome={} openId={} ok={}", e.instanceId(), e.outcome(), openId, ok);
    }
}
