package com.lrj.langchain4j.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.CosineSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 语义切分（semantic / embedding-based chunking）：不靠标点/标题硬切，而是按<strong>主题连续性</strong>下刀。
 * 把文本切成句子、逐句 embed、算相邻句的 cosine 距离，在距离「断崖」（超过分位阈值）处断开——
 * 主题连续的句子留在同一 chunk，话题切换才切。
 *
 * <p>对照 {@code recursive}（按字符/token 硬切，常把一句话或一个主题腰斩）与 {@code markdown-header}
 * （依赖文档有标题结构）：语义切分适合<strong>无明显结构、长段落、主题在文中渐变</strong>的文本
 * （会议纪要 / 访谈 / 论文正文），让每个 chunk 是一个语义自洽的单元，召回时不会「半个主题」或
 * 「一块塞两个不相关话题」。
 *
 * <p>算法（Kamradt「5 levels」/ LlamaIndex SemanticSplitter 同款思路）：
 * <ol>
 *   <li>{@link #splitSentences} 切句（中英文句末标点，小数点不误切）</li>
 *   <li>每句与前后各 {@code bufferSize} 句拼成窗口再 embed（{@code bufferSize>0} 平滑单句噪声，
 *       让边界判断更稳）</li>
 *   <li>相邻窗口算 cosine 距离 {@code 1 - similarity}；阈值取所有距离的第 {@code breakpointPercentile}
 *       分位 —— 只有显著高于「常态」的间隙才算话题切换（默认 95 分位 → 约 5% 的间隙成为断点）</li>
 *   <li>距离 {@code > 阈值} 处断开，断点之间的句子合成一个 chunk</li>
 *   <li>超过 {@code maxSize} 的语义块用 {@code fallbackForLongChunk}（recursive）再切；
 *       不足 {@code minSize} 的碎块向前并块</li>
 * </ol>
 *
 * <p><strong>代价</strong>：入库时每句多一次 embedding 调用（切分阶段），换来更贴主题的边界。
 * 大语料用云 embedding 时注意成本；本地 Ollama/vLLM embedding 影响小。
 *
 * <p><strong>韧性</strong>：embedding 调用失败时不让入库整体崩——降级用 {@code fallbackForLongChunk}
 * 把整篇按 recursive 切（log warn）。
 *
 * <p>size 阈值（{@code maxSize}/{@code minSize}）的计量单位由 {@code tokenEstimator} 决定
 * （null=字符，非 null=token），与 {@link DocumentSplitterFactory} 的 {@code unit} 对齐。
 */
public class SemanticChunkingSplitter implements DocumentSplitter {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunkingSplitter.class);

    /** 句末标点：中文 。！？； + 英文 .!?; + 省略号 + 换行。 */
    private static final String ENDERS = "。！？；!?;…\n";

    private final EmbeddingModel embeddingModel;
    private final int bufferSize;
    private final double breakpointPercentile;
    private final int maxSize;
    private final int minSize;
    private final DocumentSplitter fallbackForLongChunk;
    private final TokenCountEstimator tokenEstimator;

    public SemanticChunkingSplitter(EmbeddingModel embeddingModel,
                                    int bufferSize,
                                    double breakpointPercentile,
                                    int maxSize,
                                    int minSize,
                                    DocumentSplitter fallbackForLongChunk,
                                    TokenCountEstimator tokenEstimator) {
        if (embeddingModel == null) throw new IllegalArgumentException("embeddingModel is required");
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize must be > 0");
        if (breakpointPercentile <= 0 || breakpointPercentile > 100) {
            throw new IllegalArgumentException("breakpointPercentile must be in (0, 100]");
        }
        this.embeddingModel = embeddingModel;
        this.bufferSize = Math.max(0, bufferSize);
        this.breakpointPercentile = breakpointPercentile;
        this.maxSize = maxSize;
        this.minSize = Math.max(0, minSize);
        this.fallbackForLongChunk = fallbackForLongChunk;
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        Metadata baseMeta = document.metadata();
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> sentences = splitSentences(text);
        // 0/1 句无从算「相邻距离」，整篇 1 块（仍走 size 上限兜底）
        if (sentences.size() <= 1) {
            return emit(List.of(text.strip()), baseMeta);
        }

        List<String> groups;
        try {
            double[] distances = adjacentDistances(sentences);
            double threshold = percentile(distances, breakpointPercentile);
            groups = group(sentences, distances, threshold);
        } catch (Exception e) {
            // embedding 后端故障：降级整篇 recursive，绝不让入库崩
            log.warn("Semantic chunking failed ({}), falling back to recursive split", e.toString());
            return fallbackForLongChunk != null
                    ? fallbackForLongChunk.split(Document.from(text, baseMeta))
                    : emit(List.of(text.strip()), baseMeta);
        }
        if (minSize > 0) {
            groups = mergeTiny(groups);
        }
        return emit(groups, baseMeta);
    }

    /** 把分好的语义块落成 segment：超长块 fallback 再切，metadata 注入连续 index。 */
    private List<TextSegment> emit(List<String> groups, Metadata baseMeta) {
        List<TextSegment> out = new ArrayList<>();
        int idx = 0;
        for (String group : groups) {
            if (group.isBlank()) continue;
            Metadata meta = baseMeta.copy();
            meta.put("index", String.valueOf(idx));
            if (sizeOf(group) <= maxSize || fallbackForLongChunk == null) {
                out.add(TextSegment.from(group, meta));
            } else {
                out.addAll(fallbackForLongChunk.split(Document.from(group, meta)));
            }
            idx++;
        }
        return out;
    }

    /** 相邻窗口的 cosine 距离 {@code 1 - similarity}；窗口 = 句子 ± bufferSize 个邻居拼接。 */
    private double[] adjacentDistances(List<String> sentences) {
        List<TextSegment> windows = new ArrayList<>(sentences.size());
        for (int i = 0; i < sentences.size(); i++) {
            int from = Math.max(0, i - bufferSize);
            int to = Math.min(sentences.size() - 1, i + bufferSize);
            windows.add(TextSegment.from(String.join(" ", sentences.subList(from, to + 1))));
        }
        List<Embedding> embeddings = embeddingModel.embedAll(windows).content();
        double[] distances = new double[embeddings.size() - 1];
        for (int i = 0; i < distances.length; i++) {
            distances[i] = 1.0 - CosineSimilarity.between(embeddings.get(i), embeddings.get(i + 1));
        }
        return distances;
    }

    /** 第 p 分位值（线性最近秩）；空数组返回 0（不切）。 */
    static double percentile(double[] values, double p) {
        if (values.length == 0) return 0.0;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        rank = Math.max(0, Math.min(sorted.length - 1, rank));
        return sorted[rank];
    }

    /** 距离 {@code > threshold} 的间隙即断点，断点之间的句子合成一块。 */
    private static List<String> group(List<String> sentences, double[] distances, double threshold) {
        List<String> groups = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            if (cur.length() > 0) cur.append(' ');
            cur.append(sentences.get(i));
            boolean breakHere = i < distances.length && distances[i] > threshold;
            if (breakHere) {
                groups.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) groups.add(cur.toString());
        return groups;
    }

    /** 不足 minSize 的块向前并入上一块（首块不足则并入下一块）。 */
    private List<String> mergeTiny(List<String> groups) {
        List<String> merged = new ArrayList<>();
        for (String g : groups) {
            if (sizeOf(g) < minSize && !merged.isEmpty()) {
                merged.set(merged.size() - 1, merged.get(merged.size() - 1) + " " + g);
            } else {
                merged.add(g);
            }
        }
        // 首块仍不足且有后继 → 并进下一块
        if (merged.size() > 1 && sizeOf(merged.get(0)) < minSize) {
            String head = merged.remove(0);
            merged.set(0, head + " " + merged.get(0));
        }
        return merged;
    }

    private int sizeOf(String s) {
        return tokenEstimator == null ? s.length() : tokenEstimator.estimateTokenCountInText(s);
    }

    /** 切句：中英文句末标点处断（小数点夹在数字间不切），保留标点；连续空白/换行规整。 */
    static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            cur.append(c);
            if (ENDERS.indexOf(c) >= 0) {
                // 小数点 / 千分位：1.5、3,000 之间的 . 不当句末
                if ((c == '.' || c == ';') && i > 0 && i < n - 1
                        && Character.isDigit(text.charAt(i - 1)) && Character.isDigit(text.charAt(i + 1))) {
                    continue;
                }
                String s = cur.toString().strip();
                if (!s.isEmpty()) out.add(s);
                cur.setLength(0);
            }
        }
        String tail = cur.toString().strip();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }
}
