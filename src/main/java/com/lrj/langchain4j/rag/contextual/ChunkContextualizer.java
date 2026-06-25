package com.lrj.langchain4j.rag.contextual;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Anthropic「Contextual Retrieval」的上下文生成器：给定整篇文档 + 其中一个 chunk，
 * 产出一句把该 chunk「安放回全文」的简短上下文（说清它讲什么、在文档里的位置、消解代词/缩写），
 * 让 chunk 脱离全文后仍自洽 —— 入库前拼到 chunk 前面再 embed，显著降召回失败率。
 *
 * <p>走独立的 temp=0 ChatModel（{@code LlmConfig.buildJudgeChatModel}），跟 {@code Critic} /
 * {@code GroundednessChecker} / {@code GraphExtractor} 同思路——situating 是确定性任务，
 * 同一 (doc, chunk) 多次应给稳定结果。由 {@code ContextualRetrievalConfig} 程序化构造，
 * <strong>不注册 ChatModel Bean</strong>（避开 {@code @AiService} 只能有一个 ChatModel 的约束）。
 */
public interface ChunkContextualizer {

    @SystemMessage("""
            You situate a chunk within its source document to improve search retrieval.

            You are given the whole DOCUMENT and one CHUNK taken from it. Write a SHORT
            context that states what this chunk is about and where it sits in the overall
            document, resolving pronouns / abbreviations / references so the chunk becomes
            self-contained for search.

            Rules:
            - One sentence, at most ~30 words.
            - Answer in the SAME language as the chunk.
            - State only facts grounded in the DOCUMENT. Do NOT invent details, do NOT
              summarize the whole document, do NOT add opinions.
            - Output ONLY the context sentence — no preamble, no labels, no quotes,
              no markdown.
            """)
    @UserMessage("""
            <document>
            {{document}}
            </document>

            Here is the chunk to situate:
            <chunk>
            {{chunk}}
            </chunk>

            Give the short situating context for this chunk.
            """)
    String contextualize(@V("document") String document, @V("chunk") String chunk);
}
