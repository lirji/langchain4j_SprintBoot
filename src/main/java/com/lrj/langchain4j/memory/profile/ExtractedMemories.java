package com.lrj.langchain4j.memory.profile;

import java.util.List;

/** {@link ProfileExtractor} 的 structured-output 包装：本轮抽出的记忆事实（无可记内容则空表）。 */
public record ExtractedMemories(List<MemoryFact> facts) {
}
