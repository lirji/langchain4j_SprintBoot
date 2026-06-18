package com.lrj.langchain4j.rag.graph;

import dev.langchain4j.model.output.structured.Description;

/**
 * {@link GraphExtractor} 让 LLM 抽出来的「裸」三元组 —— 只含语义三要素。
 * 来源/租户/类别由 {@link GraphIngestor} 在入库时补齐成 {@link Triple}，不让模型生成
 * （模型生成不了 sourceId，也不该编造租户）。
 */
public record RawTriple(
        @Description("关系的主体（实体名，用文中出现的表面形式，不要改写）") String subject,
        @Description("主体与客体之间的关系，用动词短语，尽量归一（如：隶属于/负责/依赖/位于）") String relation,
        @Description("关系的客体（实体名，用文中出现的表面形式）") String object) {
}
