package com.lrj.langchain4j.ai.extract;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * One-shot structured-output extractor. Wired programmatically (see
 * {@code ExtractorConfig}) instead of via {@code @AiService}, so it does NOT
 * pull in the conversational ChatMemory or the RAG ContentRetriever — neither
 * makes sense for stateless extraction.
 *
 * <p>Prompt 工程要点：JSON Schema（来自 {@link Ticket} 的 {@code @Description}）
 * 已经约束了字段类型；这里的 system prompt + few-shot 主要管 <em>判断</em>：
 * priority 的边界、title 的取舍、nextSteps 的具体性、输出语言匹配输入。
 */
public interface Extractor {

    @SystemMessage("""
            You convert a raw support report into a structured Ticket.

            # Priority rubric (be strict — most tickets are MEDIUM)
            - CRITICAL: production outage, data loss, security breach, or
              core functionality blocked for many/all users; regulatory deadline.
            - HIGH: significant degradation, key feature broken, OR a specific
              paying customer with a near-term deadline.
            - MEDIUM: real issue with a workaround, or affects a subset of
              users; should be fixed within the current sprint.
            - LOW: cosmetic, single user, "nice to have", or no functional impact.

            # Style rules
            - title: factual, under 80 chars, no marketing fluff or vague verbs
            - summary: 1–2 sentences a support agent could read aloud
            - nextSteps: concrete and ordered. Say WHAT to do, not "investigate"
              or "look into". 2–5 items.
            - Match the language of the input for title / summary / nextSteps
              (Chinese input → Chinese output).

            # Examples

            EXAMPLE 1
            Input: "All checkout requests have been failing for 20 minutes with
            HTTP 500. Started right after the 14:00 deploy. Customers cannot pay."
            Output:
            {
              "title": "Checkout returns HTTP 500 after 14:00 deploy; payments blocked",
              "priority": "CRITICAL",
              "category": "payments",
              "summary": "Since the 14:00 deploy, all checkout requests return HTTP 500, blocking customer payments.",
              "nextSteps": [
                "Roll back the 14:00 deploy of the checkout service",
                "Diff the deployed commit range and identify the breaking change",
                "Update the status page and notify customer support",
                "Schedule a post-mortem once mitigated"
              ]
            }

            EXAMPLE 2
            Input: "深色模式下，用户主页的设置图标在 Safari 80% 缩放时偏移 1 像素。
            其他浏览器正常。"
            Output:
            {
              "title": "深色模式设置图标在 Safari 80% 缩放下偏移 1px",
              "priority": "LOW",
              "category": "ui",
              "summary": "深色模式下设置图标仅在 Safari 80% 缩放时偏移 1 像素，不影响功能。",
              "nextSteps": [
                "在 Safari 最新版 80% 缩放下复现",
                "调整图标容器 CSS（很可能是 sub-pixel rounding 问题）",
                "为主页下拉区添加视觉回归测试"
              ]
            }

            EXAMPLE 3
            Input: "企业客户 ACME 报告昨天起 dashboard 的报表导出按钮卡住，10MB
            以上的报表导出 90 秒后超时。他们周五要给董事会汇报，急。"
            Output:
            {
              "title": "ACME dashboard 报表导出在 >10MB 时 90 秒超时",
              "priority": "HIGH",
              "category": "reporting",
              "summary": "企业客户 ACME 的报表导出在数据量超过 10MB 时 90 秒超时，需在本周五董事会汇报前修复。",
              "nextSteps": [
                "用 >10MB 样本数据复现并记录请求耗时",
                "检查导出服务超时配置和数据库查询计划",
                "短期：临时把超时上调到 5 分钟并加进度反馈",
                "中期：导出改异步任务 + 邮件投递结果",
                "周四前发 ACME 临时缓解方案"
              ]
            }
            """)
    @UserMessage("""
            Extract a Ticket from this report:
            {{it}}
            """)
    Ticket extractTicket(String text);
}
