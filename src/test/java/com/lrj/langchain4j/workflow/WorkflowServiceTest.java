package com.lrj.langchain4j.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯逻辑单测：businessKey 构造（#2 幂等的去重键）。不拉 Spring context、不连 Flowable / 模型，
 * 跟仓库其余 JUnit 一样只覆盖确定性逻辑。
 */
class WorkflowServiceTest {

    @Test
    void buildBusinessKey_withDedupeId_isStableComposite() {
        String key = WorkflowService.buildBusinessKey("tenantA", "u1", "msg-123");
        assertEquals("tenantA:u1:msg-123", key, "有 dedupeId 应拼成 tenant:chatId:dedupeId");

        // 同样入参必须得到同一个 key（这才能去重）
        assertEquals(key, WorkflowService.buildBusinessKey("tenantA", "u1", "msg-123"));
    }

    @Test
    void buildBusinessKey_trimsDedupeId() {
        assertEquals("tenantA:u1:msg-123",
                WorkflowService.buildBusinessKey("tenantA", "u1", "  msg-123  "));
    }

    @Test
    void buildBusinessKey_withoutDedupeId_isRandomNonDeduping() {
        for (String blank : new String[]{null, "", "   "}) {
            String k1 = WorkflowService.buildBusinessKey("tenantA", "u1", blank);
            String k2 = WorkflowService.buildBusinessKey("tenantA", "u1", blank);
            assertTrue(k1.startsWith("tenantA:u1:"), "应保留 tenant:chatId 前缀，输入=[" + blank + "]");
            assertFalse(k1.isBlank(), "key 不能为空");
            assertNotEquals(k1, k2, "无 dedupeId 时每次都应是不同的随机 key（不去重），输入=[" + blank + "]");
        }
    }
}
