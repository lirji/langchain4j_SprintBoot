package com.lrj.langchain4j.rag;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
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
 *   <li>{@code parent-child} — {@link ParentChildSplitter}：small-to-big。child 用 {@code max-size}/{@code overlap}
 *       切小块去 embed（召回精准），parent 用 {@code app.rag.chunking.parent.*} 切大块喂上下文（命中 child 后
 *       由 {@link TaggedSourceContentInjector} 换成 parent 文本）。parent 切法可选 recursive / markdown-header</li>
 *   <li>{@code semantic} — {@link SemanticChunkingSplitter}：按主题连续性切，逐句 embed 在 cosine 距离
 *       断崖处下刀（{@code app.rag.chunking.semantic.*}）。适合无结构长文，代价是入库每句多一次 embed</li>
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
    private final int minSectionSize;
    private final String tokenizerModel;
    private final String parentStrategy;
    private final int parentSize;
    private final int parentOverlap;
    private final int semanticBufferSize;
    private final double semanticPercentile;
    private final int semanticMaxSize;
    private final int semanticMinSize;
    // semantic 策略才用到的 EmbeddingModel（切分阶段逐句 embed）。始终注入（单例 Bean 恒存在），
    // 仅 strategy=semantic 时才真正调用 → 其它策略零开销。
    private final EmbeddingModel embeddingModel;

    public DocumentSplitterFactory(
            @Value("${app.rag.chunking.strategy:recursive}") String strategy,
            @Value("${app.rag.chunking.unit:chars}") String unit,
            // 兼容旧 key：max-size 优先，缺省回退 max-chars（默认 300）
            @Value("${app.rag.chunking.max-size:${app.rag.chunking.max-chars:300}}") int maxSize,
            @Value("${app.rag.chunking.overlap:50}") int overlap,
            // markdown-header 极小 section 合并阈值（单位同 unit）；0 = 关闭合并。代码默认 0（保守），
            // 随 application.yml 默认开（见 app.rag.chunking.min-section-size）。
            @Value("${app.rag.chunking.min-section-size:0}") int minSectionSize,
            @Value("${app.rag.chunking.tokenizer-model:gpt-4o-mini}") String tokenizerModel,
            // parent-child 专用：parent（喂上下文的大块）切法与窗口；strategy=parent-child 时才用到
            @Value("${app.rag.chunking.parent.strategy:recursive}") String parentStrategy,
            @Value("${app.rag.chunking.parent.size:1200}") int parentSize,
            @Value("${app.rag.chunking.parent.overlap:0}") int parentOverlap,
            // semantic 专用：句邻居缓冲 / 断点分位 / 块大小上下限；strategy=semantic 时才用到
            @Value("${app.rag.chunking.semantic.buffer-size:1}") int semanticBufferSize,
            @Value("${app.rag.chunking.semantic.breakpoint-percentile:95}") double semanticPercentile,
            @Value("${app.rag.chunking.semantic.max-size:1000}") int semanticMaxSize,
            @Value("${app.rag.chunking.semantic.min-size:0}") int semanticMinSize,
            EmbeddingModel embeddingModel) {
        this.strategy = strategy == null ? "recursive" : strategy.trim().toLowerCase();
        this.unit = unit == null ? "chars" : unit.trim().toLowerCase();
        this.maxSize = maxSize;
        this.overlap = overlap;
        this.minSectionSize = Math.max(0, minSectionSize);
        this.tokenizerModel = tokenizerModel;
        this.parentStrategy = parentStrategy == null ? "recursive" : parentStrategy.trim().toLowerCase();
        this.parentSize = parentSize;
        this.parentOverlap = parentOverlap;
        this.semanticBufferSize = semanticBufferSize;
        this.semanticPercentile = semanticPercentile;
        this.semanticMaxSize = semanticMaxSize;
        this.semanticMinSize = semanticMinSize;
        this.embeddingModel = embeddingModel;
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
            // markdown-header 的 section 阈值也按同一单位计量（estimator 透传，null=按字符）；
            // minSectionSize 启用极小 section 合并（自适应标题层级 + breadcrumb 注入是 splitter 内置行为）
            case "markdown-header" -> new MarkdownHeaderSplitter(maxSize, recursive, estimator, minSectionSize);
            // parent-child：child = 上面的 recursive(maxSize/overlap) 小块（被 embed）；
            // parent = parent.* 配的大块（被换进 prompt）。两侧单位一致由同一 estimator 决定。
            case "parent-child" -> new ParentChildSplitter(buildParentSplitter(estimator), recursive);
            // semantic：逐句 embed，在 cosine 距离断崖处切；超长块 fallback 到 semantic.max-size 的 recursive
            case "semantic" -> buildSemanticSplitter(estimator);
            default -> {
                log.warn("Unknown app.rag.chunking.strategy '{}', falling back to recursive", strategy);
                yield recursive;
            }
        };
        if ("parent-child".equals(strategy)) {
            log.info("Chunking: strategy=parent-child unit={} child(max-size={} overlap={}) parent(strategy={} size={} overlap={}){}",
                    unit, maxSize, overlap, parentStrategy, parentSize, parentOverlap,
                    tokenMode ? " tokenizer~" + tokenizerModel : "");
        } else if ("semantic".equals(strategy)) {
            log.info("Chunking: strategy=semantic unit={} buffer-size={} breakpoint-percentile={} max-size={} min-size={}{}",
                    unit, semanticBufferSize, semanticPercentile, semanticMaxSize, semanticMinSize,
                    tokenMode ? " tokenizer~" + tokenizerModel : "");
        } else {
            log.info("Chunking: strategy={} unit={} max-size={} overlap={} min-section={}{}",
                    strategy, unit, maxSize, overlap, minSectionSize,
                    tokenMode ? " tokenizer~" + tokenizerModel : "");
        }
        return result;
    }

    /** 构造语义切分器：超长语义块的 fallback 用 semantic.max-size 的 recursive（而非全局 max-size，避免把大块切回 300）。 */
    private DocumentSplitter buildSemanticSplitter(TokenCountEstimator estimator) {
        DocumentSplitter fallback = estimator != null
                ? DocumentSplitters.recursive(semanticMaxSize, overlap, estimator)
                : DocumentSplitters.recursive(semanticMaxSize, overlap);
        return new SemanticChunkingSplitter(embeddingModel, semanticBufferSize, semanticPercentile,
                semanticMaxSize, semanticMinSize, fallback, estimator);
    }

    /** 构造 parent（喂上下文的大块）splitter：parent.strategy 选 recursive / markdown-header，按 parent.size 切。 */
    private DocumentSplitter buildParentSplitter(TokenCountEstimator estimator) {
        DocumentSplitter parentRecursive = estimator != null
                ? DocumentSplitters.recursive(parentSize, parentOverlap, estimator)
                : DocumentSplitters.recursive(parentSize, parentOverlap);
        return switch (parentStrategy) {
            case "recursive" -> parentRecursive;
            // section 作 parent —— 一个完整主题当上下文窗口，small-to-big 的最佳搭配
            case "markdown-header" -> new MarkdownHeaderSplitter(parentSize, parentRecursive, estimator, minSectionSize);
            default -> {
                log.warn("Unknown app.rag.chunking.parent.strategy '{}', falling back to recursive", parentStrategy);
                yield parentRecursive;
            }
        };
    }

    public String strategy() { return strategy; }
    public String unit() { return unit; }
    public int maxSize() { return maxSize; }
    public int overlap() { return overlap; }
}
