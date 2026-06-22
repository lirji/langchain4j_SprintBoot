package com.lrj.langchain4j.channel;

import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import com.lrj.langchain4j.workflow.WorkflowService;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CustomerServiceBrain 确定性逻辑单测（不连模型/DB）：
 * CHAT 路由 / 工作流关闭时降级对话 / 工作流播报文案（静态助手）。
 * 实际 {@code workflow.start()} 的工作流编排属集成，按项目惯例走手动/eval，不在此 mock。
 */
class CustomerServiceBrainTest {

    private static final ResolvedAssistantStyle STYLE =
            new ResolvedAssistantStyle("中文", "简洁", "cite", "");

    /** 回显式 stub Assistant：chat 把输入原样带回，便于断言路由走到了对话。 */
    private static Assistant echoAssistant() {
        return new Assistant() {
            @Override public String chat(String chatId, String language, String tone,
                                         String citationPolicy, String extra, String userMessage) {
                return "ECHO:" + userMessage;
            }
            @Override public TokenStream chatStream(String chatId, String language, String tone,
                                                    String citationPolicy, String extra, String userMessage) {
                return null;
            }
        };
    }

    /** 返回固定值（可为 null）的 ObjectProvider，模拟"工作流未启用"。 */
    private static <T> ObjectProvider<T> provider(T val) {
        return new ObjectProvider<>() {
            @Override public T getObject() {
                if (val == null) throw new NoSuchBeanDefinitionException("none");
                return val;
            }
            @Override public T getObject(Object... args) { return getObject(); }
            @Override public T getIfAvailable() { return val; }
            @Override public T getIfUnique() { return val; }
        };
    }

    @Test
    void chatRoute_whenNotWorkflowIntent() {
        CustomerServiceBrain brain = new CustomerServiceBrain(echoAssistant(), STYLE, provider(null));
        CustomerServiceBrain.BrainReply r = brain.reply("t1", "c1", "你们几点上班？");
        assertThat(r.route()).isEqualTo(CustomerServiceBrain.Route.CHAT);
        assertThat(r.text()).isEqualTo("ECHO:你们几点上班？");
        assertThat(r.workflowInstanceId()).isNull();
    }

    @Test
    void degradesToChat_whenWorkflowDisabled_evenForRefundIntent() {
        // "退款" 命中工作流意图，但 workflow 未启用（provider 给 null）→ 退化为对话，跟飞书一致
        CustomerServiceBrain brain = new CustomerServiceBrain(echoAssistant(), STYLE, provider(null));
        CustomerServiceBrain.BrainReply r = brain.reply("t1", "c1", "我要退款");
        assertThat(r.route()).isEqualTo(CustomerServiceBrain.Route.CHAT);
        assertThat(r.text()).startsWith("ECHO:");
    }

    @Test
    void workflowSpoken_waiting_announcesHumanReview() {
        WorkflowService.StartResult waiting = new WorkflowService.StartResult(
                "inst-12345678abc", WorkflowService.STATUS_WAITING, null, "task-1", "HIGH", false);
        String spoken = CustomerServiceBrain.workflowSpoken(waiting);
        assertThat(spoken).contains("转接人工审核").contains("inst-123");   // 短 id 截断到 8 字符
    }

    @Test
    void workflowSpoken_completed_usesReply_orFallback() {
        WorkflowService.StartResult withReply = new WorkflowService.StartResult(
                "i", WorkflowService.STATUS_COMPLETED, "已为您自动办理退款。", null, "LOW", false);
        assertThat(CustomerServiceBrain.workflowSpoken(withReply)).isEqualTo("已为您自动办理退款。");

        WorkflowService.StartResult nullReply = new WorkflowService.StartResult(
                "i", WorkflowService.STATUS_COMPLETED, null, null, "LOW", false);
        assertThat(CustomerServiceBrain.workflowSpoken(nullReply)).isEqualTo("已受理您的请求。");
    }
}
