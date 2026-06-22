package com.lrj.langchain4j.channel.feishu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 纯逻辑单测：入站意图分类（退款/投诉 → WORKFLOW，其余 → CHAT）。
 */
class FeishuIntentTest {

    @Test
    void refundIntents_routeToWorkflow() {
        for (String m : new String[]{
                "我要退款", "这个订单想退货", "申请退钱", "我要投诉你们客服", "要求赔偿",
                "I want a refund", "please process a RETURN"}) {
            assertEquals(FeishuIntent.Route.WORKFLOW, FeishuIntent.classify(m), "应判工作流：" + m);
        }
    }

    @Test
    void normalQuestions_routeToChat() {
        for (String m : new String[]{
                "你们几点上班？", "介绍一下产品功能", "怎么修改收货地址", "How do I reset my password?"}) {
            assertEquals(FeishuIntent.Route.CHAT, FeishuIntent.classify(m), "应判对话：" + m);
        }
    }

    @Test
    void blankOrNull_routeToChat() {
        assertEquals(FeishuIntent.Route.CHAT, FeishuIntent.classify(null));
        assertEquals(FeishuIntent.Route.CHAT, FeishuIntent.classify("   "));
    }
}
