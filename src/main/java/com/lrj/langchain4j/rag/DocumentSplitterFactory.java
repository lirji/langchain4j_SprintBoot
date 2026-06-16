package com.lrj.langchain4j.rag;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring 单例：按 {@code app.rag.chunking.*} 配置生产 {@link DocumentSplitter}。
 *
 * <p>抽出来让 {@link RagIngestionService}（批量从 documents/ 目录入库）和
 * {@code DocumentService}（单文档 upload）共享同一份 chunking 策略，不必各 build 一遍。
 *
 * <p>支持策略：
 * <ul>
 *   <li>{@code recursive}（默认）— {@code DocumentSplitters.recursive(maxSize, overlap)}</li>
 *   <li>{@code markdown-header} — {@link MarkdownHeaderSplitter}：按 ## 切 section，超长 fallback recursive</li>
 * </ul>
 *
 * <p>计量单位 {@code app.rag.chunking.unit}：
 * <ul>
 *   <li>{@code chars}（默认）— {@code max-size}/{@code overlap} 按字符数。零依赖、稳定可控，
 *       但同样字符数下中英文真实 token 数差 2~3 倍，token 预算不均</li>
 *   <li>{@code tokens} — 给 splitter 挂 {@link OpenAiTokenCountEstimator}（tiktoken 近似），
 *       {@code max-size}/{@code overlap} 单位变 token。LLM context 和计费都按 token 算，更贴生产。
 *       本地模型（Ollama/bge-m3）不暴露 tokenizer，用 OpenAI 估算，偏差通常 10~15%，
 *       对 chunk 这种软目标可接受。必须保证 {@code max-size + overlap ≤ embedding 模型 max input}，
 *       否则尾部被静默截断</li>
 * </ul>
 *
 * <p>{@code max-size} 兼容旧 key {@code max-chars}：两者都读，{@code max-size} 优先；
 * 未设 {@code max-size} 时回退到 {@code max-chars}（默认 300）。
 */
@Component
public class DocumentSplitterFactory {

    private static final Logger log = LoggerFactory.getLogger(DocumentSplitterFactory.class);

    private final String strategy;
    private final String unit;
    private final int maxSize;
    private final int overlap;
    private final String tokenizerModel;

    public DocumentSplitterFactory(
            @Value("${app.rag.chunking.strategy:recursive}") String strategy,
            @Value("${app.rag.chunking.unit:chars}") String unit,
            // 兼容旧 key：max-size 优先，缺省回退 max-chars（默认 300）
            @Value("${app.rag.chunking.max-size:${app.rag.chunking.max-chars:300}}") int maxSize,
            @Value("${app.rag.chunking.overlap:50}") int overlap,
            @Value("${app.rag.chunking.tokenizer-model:gpt-4o-mini}") String tokenizerModel) {
        this.strategy = strategy == null ? "recursive" : strategy.trim().toLowerCase();
        this.unit = unit == null ? "chars" : unit.trim().toLowerCase();
        this.maxSize = maxSize;
        this.overlap = overlap;
        this.tokenizerModel = tokenizerModel;
    }

    public DocumentSplitter create() {
        boolean tokenMode = "tokens".equals(unit);
        // token 模式：挂 OpenAI tokenizer，recursive 的 maxSize/overlap 单位变 token
        TokenCountEstimator estimator = tokenMode ? new OpenAiTokenCountEstimator(tokenizerModel) : null;
        DocumentSplitter recursive = tokenMode
                ? DocumentSplitters.recursive(maxSize, overlap, estimator)
                : DocumentSplitters.recursive(maxSize, overlap);

        DocumentSplitter result = switch (strategy) {
            case "recursive" -> recursive;
            // markdown-header 的 section 阈值也按同一单位计量（estimator 透传，null=按字符）
            case "markdown-header" -> new MarkdownHeaderSplitter(maxSize, recursive, estimator);
            default -> {
                log.warn("Unknown app.rag.chunking.strategy '{}', falling back to recursive", strategy);
                yield recursive;
            }
        };
        log.info("Chunking: strategy={} unit={} max-size={} overlap={}{}",
                strategy, unit, maxSize, overlap,
                tokenMode ? " tokenizer~" + tokenizerModel : "");
        return result;
    }

    public String strategy() { return strategy; }
    public String unit() { return unit; }
    public int maxSize() { return maxSize; }
    public int overlap() { return overlap; }
}
