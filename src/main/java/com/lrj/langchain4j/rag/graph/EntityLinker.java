package com.lrj.langchain4j.rag.graph;

import java.util.Set;

/**
 * query → 图中种子实体（表面形式）的链接。G1 只有 {@link TokenEntityLinker}（零 LLM）；
 * {@code entity-linking=llm} 的小抽取链接器留作 G2（更准、能处理同义/变体）。
 */
public interface EntityLinker {

    Set<String> link(String query, String tenant, String category);
}
