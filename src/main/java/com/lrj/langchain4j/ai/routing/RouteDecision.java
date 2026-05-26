package com.lrj.langchain4j.ai.routing;

import dev.langchain4j.model.output.structured.Description;

/**
 * Classifier 输出：分类 + 简短理由（一句话，方便日志和调试 / API 返回给调用方观察）。
 */
public record RouteDecision(
        @Description("分类结果。RAG | TOOL | CHAT 三选一")
        RouteKind kind,

        @Description("一句中文说明为什么这么分。例如『问到当前时间，需要 DateTimeTool』。不超过 40 字。")
        String reason
) {}
