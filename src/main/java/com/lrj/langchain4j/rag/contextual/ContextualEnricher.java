package com.lrj.langchain4j.rag.contextual;

import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Contextual Retrieval 的入库改写：在切分之后、embed 之前，给每个 chunk 用
 * {@link ChunkContextualizer} 生成一句「安放回全文」的上下文，拼到 chunk 前面再入库。
 *
 * <p>解决向量召回的一个真实盲区：chunk 切下来后常常指代不明（"它支持 X"——「它」是谁？
 * "该方案的成本…"——哪个方案？），脱离全文语义被稀释、召不准。给 chunk 加一句文档级上下文
 * （"这是 LangChain4j 多 provider 切换章节，说明 vLLM 的成本…"）后，embedding 与 BM25
 * 索引到的文本都自洽了。Anthropic 实测召回失败率降约 35%（配 BM25/rerank 更多）。
 *
 * <p><strong>接线</strong>：{@code RagIngestionService}（批量）与 {@code DocumentService}（单上传）
 * 经 {@code ObjectProvider} 软依赖调用——{@code app.rag.contextual.enabled=false}（默认）时本 Bean
 * 不存在 → 入库链与历史完全一致、零开销。
 *
 * <p><strong>取舍 / 韧性</strong>：
 * <ul>
 *   <li>每个 chunk 一次 temp=0 LLM 调用（一次性入库成本）；大文档把整篇截到 {@code maxDocChars}
 *       喂模型以控上下文/成本（生产可叠加 provider 的 prompt caching 缓存文档段进一步降本）</li>
 *   <li>单 chunk 文档（{@code segments < minSegments}）跳过——整块即全文、无指代歧义</li>
 *   <li>某个 chunk 的 LLM 调用失败 → 保留原文（不前缀），绝不让入库整体崩</li>
 *   <li><strong>串行执行</strong>（不并行）：保持调用线程的 {@code TenantContext}，让上下文生成的
 *       token 正确计入该租户的配额/指标。并行需 MDC/租户透传，列为未来项</li>
 * </ul>
 */
public class ContextualEnricher {

    private static final Logger log = LoggerFactory.getLogger(ContextualEnricher.class);

    private final ChunkContextualizer contextualizer;
    private final int maxDocChars;
    private final int minSegments;

    public ContextualEnricher(ChunkContextualizer contextualizer, int maxDocChars, int minSegments) {
        this.contextualizer = contextualizer;
        this.maxDocChars = Math.max(0, maxDocChars);
        this.minSegments = Math.max(1, minSegments);
    }

    /**
     * 给 {@code segments} 逐块加文档级上下文前缀（保留 metadata）。
     *
     * @param documentText 整篇文档原文（生成上下文的依据；超长截到 {@code maxDocChars}）
     * @param segments     已切分的 chunk（未改写）
     * @return 改写后的 segment 列表（顺序、metadata 不变，仅 text 前缀化）
     */
    public List<TextSegment> enrich(String documentText, List<TextSegment> segments) {
        if (segments == null || segments.size() < minSegments || documentText == null || documentText.isBlank()) {
            return segments;
        }
        String docContext = truncate(documentText, maxDocChars);
        List<TextSegment> out = new ArrayList<>(segments.size());
        int enriched = 0;
        for (TextSegment seg : segments) {
            String context = situate(docContext, seg.text());
            if (context == null || context.isBlank()) {
                out.add(seg);
            } else {
                // metadata 原样保留（index / file_name / tenantId / parent_* 等都不动），只前缀化 text
                out.add(TextSegment.from(context.strip() + "\n\n" + seg.text(), seg.metadata().copy()));
                enriched++;
            }
        }
        log.info("Contextual Retrieval: enriched {}/{} chunks with document-level context", enriched, segments.size());
        return out;
    }

    /** 单 chunk 的上下文生成，吞异常降级（返回 null = 用原文）。 */
    private String situate(String docContext, String chunkText) {
        try {
            return contextualizer.contextualize(docContext, chunkText);
        } catch (Exception e) {
            log.warn("Contextual Retrieval: contextualize failed for a chunk ({}), keeping original text", e.toString());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (max <= 0 || s.length() <= max) return s;
        return s.substring(0, max);
    }
}
