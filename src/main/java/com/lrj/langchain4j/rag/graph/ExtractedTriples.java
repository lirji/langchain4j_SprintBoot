package com.lrj.langchain4j.rag.graph;

import java.util.List;

/** {@link GraphExtractor} 的 structured-output 包装（单 chunk 抽出的一批三元组）。 */
public record ExtractedTriples(List<RawTriple> triples) {
}
