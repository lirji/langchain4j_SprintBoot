package com.lrj.langchain4j.memory.profile;

import dev.langchain4j.model.output.structured.Description;

/** {@link ProfileExtractor} 抽出的一条「裸」记忆事实（id/时间/来源由 service 补齐成 {@link MemoryItem}）。 */
public record MemoryFact(
        @Description("一句话、跨会话值得长期记住的用户事实（偏好/稳定属性/反复诉求），用第三人称陈述") String text,
        @Description("分类：preference（偏好）| attribute（稳定属性）| issue（反复诉求/历史问题）| other") String type) {
}
