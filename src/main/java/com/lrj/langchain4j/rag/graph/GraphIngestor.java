package com.lrj.langchain4j.rag.graph;

import com.lrj.langchain4j.rag.TaggedSourceContentInjector;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 入库侧的建图钩子：对每个<strong>已切好</strong>的 chunk 跑 {@link GraphExtractor} 抽三元组，
 * 补上 {@code sourceId}（{@link TaggedSourceContentInjector#inferId}）+ 租户/类别，写进 {@link GraphStore}。
 * 经 {@code ObjectProvider} 软依赖挂在 {@code RagIngestionService}/{@code DocumentService} 尾部。
 *
 * <p>G3/G4 增强：
 * <ul>
 *   <li><strong>async</strong>（{@code app.rag.graph.async}）：true 时整批投后台 {@link Executor}，
 *       {@code ingest} 立即返回，不让 N 次抽取 LLM 调用阻塞入库请求。大语料必开。</li>
 *   <li><strong>relationWhitelist</strong>（{@code extract.relation-types}）：非空时只保留 relation
 *       归一后落在白名单内的边（受限 schema），并把允许集喂进抽取 prompt。</li>
 *   <li><strong>aliases</strong>（{@code aliases}）：入库时把 subject/object 的表面形式规范化成 canonical
 *       （轻量实体消歧 v1，如「张三经理」→「张三」），避免同实体多表面形式在图里裂成多个节点。</li>
 * </ul>
 * 单 chunk 抽取失败被吞（log + 跳过），不让坏 chunk 打挂整次入库。
 */
public class GraphIngestor {

    private static final Logger log = LoggerFactory.getLogger(GraphIngestor.class);

    private final GraphExtractor extractor;
    private final GraphStore store;
    private final int maxTriplesPerChunk;
    private final Executor executor;
    private final boolean async;
    private final Set<String> relationWhitelist;   // 归一后的允许 relation；空 = 不限
    private final Map<String, String> aliases;      // 原始表面形式 → canonical
    private final String relationHint;              // 喂给抽取 prompt 的允许集说明（白名单空时为 ""）

    public GraphIngestor(GraphExtractor extractor, GraphStore store, int maxTriplesPerChunk,
                         Executor executor, boolean async,
                         Set<String> relationWhitelist, Map<String, String> aliases) {
        this.extractor = extractor;
        this.store = store;
        this.maxTriplesPerChunk = maxTriplesPerChunk;
        this.executor = executor;
        this.async = async;
        this.relationWhitelist = relationWhitelist == null ? Set.of()
                : relationWhitelist.stream().map(GraphIngestor::norm).collect(Collectors.toSet());
        this.aliases = aliases == null ? Map.of() : aliases;
        this.relationHint = this.relationWhitelist.isEmpty() ? ""
                : "Allowed relations — use ONLY these, skip any relationship that doesn't fit: "
                        + String.join(", ", (relationWhitelist == null ? Set.<String>of() : relationWhitelist));
    }

    /**
     * @return 同步模式返回抽取的三元组数；async 模式投后台后返回 -1（实际数量在后台日志里）。
     */
    public int ingest(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) return 0;
        if (async) {
            executor.execute(() -> doIngest(segments));
            log.info("graph ingest submitted async for {} segments", segments.size());
            return -1;
        }
        return doIngest(segments);
    }

    private int doIngest(List<TextSegment> segments) {
        int total = 0;
        int droppedBySchema = 0;
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            String tenantId = seg.metadata() == null ? null : seg.metadata().getString("tenantId");
            String category = seg.metadata() == null ? null : seg.metadata().getString("category");
            String sourceId = TaggedSourceContentInjector.inferId(seg, i);
            try {
                ExtractedTriples ex = extractor.extract(seg.text(), relationHint);
                if (ex == null || ex.triples() == null) continue;
                List<Triple> batch = new ArrayList<>();
                for (RawTriple r : ex.triples()) {
                    if (isBlank(r.subject()) || isBlank(r.relation()) || isBlank(r.object())) continue;
                    if (!relationWhitelist.isEmpty() && !relationWhitelist.contains(norm(r.relation()))) {
                        droppedBySchema++;
                        continue;       // 受限 schema：白名单外的关系丢弃
                    }
                    batch.add(new Triple(canonical(r.subject()), r.relation().trim(), canonical(r.object()),
                            sourceId, tenantId, category));
                    if (batch.size() >= maxTriplesPerChunk) break;
                }
                store.add(batch);
                total += batch.size();
            } catch (Exception e) {
                log.warn("graph extraction failed for source {} (skipped): {}", sourceId, e.toString());
            }
        }
        log.info("graph ingest: {} triples from {} segments (dropped {} off-schema; graph size now {})",
                total, segments.size(), droppedBySchema, store.size());
        return total;
    }

    /** 别名规范化：表面形式命中 alias 表则替换成 canonical，否则原样 trim。 */
    private String canonical(String surface) {
        String s = surface.trim();
        return aliases.getOrDefault(s, s);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
