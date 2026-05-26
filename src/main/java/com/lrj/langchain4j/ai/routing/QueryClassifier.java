package com.lrj.langchain4j.ai.routing;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * LLM-as-router：把单条用户 query 分类到 RAG / TOOL / CHAT 三档。
 *
 * <p>不走 {@code @AiService} 自动装配，由 {@link com.lrj.langchain4j.config.QueryRoutingConfig}
 * 程序化构建 —— 用专门的 ChatModel 实例（temperature=0，跟 Judge 同样思路保证分类稳定）。
 *
 * <p>少而精的 few-shot 锚定 3 类各 1-2 例 + 1 反例（不要把"什么是 RAG"误判为 RAG）。
 * 结构化输出 {@link RouteDecision} 由 {@code @Description} 转 JSON Schema，
 * 模型几乎不会跑偏。
 */
public interface QueryClassifier {

    @SystemMessage("""
            你是一个 query 分类器。把用户问题归类为下面三档之一：

            - RAG：问题指向**项目内文档/知识库的具体内容**（提到「文档」「手册」「资料里」「根据上述材料」「本项目里」「配置项」之类），
              需要从文档检索才能正确回答。
            - TOOL：问题需要调用**注册的工具**才能正确回答，比如：当前时间、日期、距离 X 还有多少天、特定时区时间。
            - CHAT：纯对话 / 概念解释 / 写代码示例 / 通用知识问答 —— 模型靠自己的训练知识就能回答，
              **既不依赖项目文档，也不需要工具**。

            # 关键反例
            - 「什么是 RAG？」→ CHAT（解释通用概念，不是查文档）
            - 「2024 年的总统是谁」→ CHAT 或 TOOL（看是否有 web search 工具；本项目没有 → CHAT）
            - 「现在几点」→ TOOL（要 currentDateTime）
            - 「根据上述资料，本项目的数据库是什么」→ RAG

            决策不确定时偏向 CHAT（成本最低，没有 false negative 风险）。

            # 例子

            Q: 用一句话解释 dependency injection
            → {"kind": "CHAT", "reason": "通用概念解释，模型知识足够"}

            Q: 现在几点？时区 Asia/Shanghai
            → {"kind": "TOOL", "reason": "问当前时间，需要 currentDateTime 工具"}

            Q: 距离 2026-12-31 还有多少天
            → {"kind": "TOOL", "reason": "需要 daysUntil 工具计算"}

            Q: 根据文档，本项目当前默认的 chat provider 是什么
            → {"kind": "RAG", "reason": "明确要求按文档回答项目配置"}

            Q: 什么是 Spring Boot
            → {"kind": "CHAT", "reason": "通用技术概念，无须文档"}

            Q: 把这段联系信息整理成一句话：张三的邮箱是 ...
            → {"kind": "CHAT", "reason": "用户直接提供信息，做文本整理即可"}
            """)
    @UserMessage("""
            分类下面这条 query：
            {{it}}
            """)
    RouteDecision classify(String query);
}
