package com.lrj.langchain4j.rag.graph;

import java.util.List;

/** {@link QueryEntityExtractor} 从一句 query 里抽出的实体提及（不含关系）。 */
public record QueryEntities(List<String> entities) {
}
