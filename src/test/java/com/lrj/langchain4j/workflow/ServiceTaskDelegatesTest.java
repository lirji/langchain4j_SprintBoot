package com.lrj.langchain4j.workflow;

import com.lrj.langchain4j.ai.extract.Ticket;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯逻辑单测：驳回话术拼装 + #3 的有界重试/降级补偿（withRetry）+ 兜底工单/答复。
 * 不拉 Spring context、不连 Flowable / 模型，跟仓库其余 JUnit 一样只覆盖确定性逻辑。
 */
class ServiceTaskDelegatesTest {

    /** 构造一个只为测 withRetry 的 delegate：其余依赖 withRetry 用不到，传 null；只给真实 props。 */
    private static ServiceTaskDelegates delegateWith(int maxAttempts, long backoffMs) {
        WorkflowProperties props = new WorkflowProperties();
        props.setLlmMaxAttempts(maxAttempts);
        props.setLlmRetryBackoffMs(backoffMs);
        return new ServiceTaskDelegates(null, null, null, null, null, props);
    }

    @Test
    void rejectionMessage_includesComment() {
        String msg = ServiceTaskDelegates.rejectionMessage("超出退款时效");
        assertTrue(msg.contains("超出退款时效"), "应带上审批意见");
        assertTrue(msg.contains("未通过"), "应是驳回话术");
    }

    @Test
    void rejectionMessage_blankComment_fallsBackToPlaceholder() {
        for (String blank : new String[]{null, "", "   "}) {
            String msg = ServiceTaskDelegates.rejectionMessage(blank);
            assertTrue(msg.contains("未提供具体原因"), "空意见应有兜底措辞，输入=[" + blank + "]");
        }
    }

    @Test
    void withRetry_successFirstTry_noDegrade() {
        ServiceTaskDelegates d = delegateWith(2, 0);
        var r = d.withRetry("t", () -> "ok", () -> "fallback");
        assertEquals("ok", r.value());
        assertFalse(r.degraded(), "首次成功不应降级");
    }

    @Test
    void withRetry_allFail_returnsFallbackDegraded() {
        ServiceTaskDelegates d = delegateWith(2, 0);
        AtomicInteger calls = new AtomicInteger();
        var r = d.withRetry("t", () -> {
            calls.incrementAndGet();
            throw new RuntimeException("LLM down");
        }, () -> "fallback");
        assertEquals("fallback", r.value(), "耗尽后走兜底");
        assertTrue(r.degraded(), "应标记降级");
        assertEquals(2, calls.get(), "maxAttempts=2 应恰好尝试 2 次");
    }

    @Test
    void withRetry_failsOnceThenSucceeds_noDegrade() {
        ServiceTaskDelegates d = delegateWith(2, 0);
        AtomicInteger calls = new AtomicInteger();
        var r = d.withRetry("t", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("transient");
            }
            return "ok-on-2nd";
        }, () -> "fallback");
        assertEquals("ok-on-2nd", r.value(), "重试后成功应取真实结果");
        assertFalse(r.degraded(), "成功不降级");
    }

    @Test
    void withRetry_maxAttemptsClampedToAtLeastOne() {
        ServiceTaskDelegates d = delegateWith(0, 0); // 非法 0 → 至少跑 1 次
        AtomicInteger calls = new AtomicInteger();
        var r = d.withRetry("t", () -> {
            calls.incrementAndGet();
            throw new RuntimeException("x");
        }, () -> "fb");
        assertEquals(1, calls.get(), "maxAttempts<1 应被夹到 1 次");
        assertTrue(r.degraded());
    }

    @Test
    void degradedTicket_forcesHighPriorityAndKeepsMessage() {
        Ticket t = ServiceTaskDelegates.degradedTicket("订单一直发不出货，急");
        assertEquals(Ticket.Priority.HIGH, t.priority(), "抽取失败应强制转人工（HIGH）");
        assertTrue(t.summary().contains("发不出货"), "summary 应保留用户原始诉求");
    }

    @Test
    void degradedTicket_blankMessage_hasPlaceholder() {
        Ticket t = ServiceTaskDelegates.degradedTicket("   ");
        assertEquals(Ticket.Priority.HIGH, t.priority());
        assertTrue(t.summary().contains("无法解析"), "空消息应有兜底 summary");
    }

    @Test
    void degradedResolveReply_mentionsManualFollowUp() {
        String r = ServiceTaskDelegates.degradedResolveReply();
        assertTrue(r.contains("人工"), "降级答复应告知转人工");
        assertTrue(r.contains("受理"), "应明确已受理，避免用户干等");
    }
}
