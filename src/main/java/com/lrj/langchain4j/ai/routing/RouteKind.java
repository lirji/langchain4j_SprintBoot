package com.lrj.langchain4j.ai.routing;

import dev.langchain4j.model.output.structured.Description;

/**
 * Query 路由三档。@Description 会被 LangChain4j 写进 JSON Schema 给到 classifier LLM —— 决策依据。
 */
public enum RouteKind {

    @Description("问题需要检索文档/知识库才能正确回答，比如包含『文档里』『手册』『资料』『根据上述材料』，或问的是项目内部信息（配置项、内部 API、文档化的设计决策）")
    RAG,

    @Description("问题需要调用工具才能正确回答，比如询问当前时间/日期/距离某天多少天、需要实时计算、需要查询外部状态")
    TOOL,

    @Description("纯对话/通用知识/解释概念/写代码示例等 —— 模型自身知识足以回答，既不需要文档检索也不需要工具调用")
    CHAT
}
