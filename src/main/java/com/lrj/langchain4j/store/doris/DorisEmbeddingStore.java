package com.lrj.langchain4j.store.doris;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal Apache Doris implementation of {@link EmbeddingStore} for TextSegment.
 * Uses Doris ANN index (HNSW) + *_distance_approximate functions over MySQL protocol.
 * <p>
 * Requires Doris 3.0+ with vector index support. This is a scaffold —
 * metadata filtering, bulk stream-load, and batched search are intentionally
 * left out and can be added when the use case calls for them.
 */
public class DorisEmbeddingStore implements EmbeddingStore<TextSegment> {

    private static final Logger log = LoggerFactory.getLogger(DorisEmbeddingStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String table;
    private final int dimension;
    private final String metric; // "cosine" | "l2"

    public DorisEmbeddingStore(String jdbcUrl, String user, String password,
                               String table, int dimension, String metric,
                               boolean createTable, int buckets) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password == null ? "" : password;
        this.table = table;
        this.dimension = dimension;
        this.metric = metric == null ? "cosine" : metric.toLowerCase(Locale.ROOT);
        if (createTable) {
            createTableIfMissing(buckets);
        }
        log.info("DorisEmbeddingStore ready: url={} table={} dim={} metric={}",
                jdbcUrl, table, dimension, this.metric);
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    private void createTableIfMissing(int buckets) {
        String metricType = "cosine".equals(metric) ? "cosine_distance" : "l2_distance";
        String ddl = """
                CREATE TABLE IF NOT EXISTS %s (
                  id VARCHAR(64) NOT NULL,
                  text STRING,
                  metadata JSON,
                  embedding ARRAY<FLOAT> NOT NULL,
                  INDEX idx_emb (embedding) USING ANN PROPERTIES("index_type"="hnsw","metric_type"="%s","dim"="%d")
                ) DUPLICATE KEY(id)
                  DISTRIBUTED BY HASH(id) BUCKETS %d
                  PROPERTIES("replication_num"="1")
                """.formatted(table, metricType, dimension, buckets);
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute(ddl);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create Doris table " + table, e);
        }
    }

    @Override
    public String add(Embedding embedding) {
        String id = UUID.randomUUID().toString();
        insert(id, embedding, null);
        return id;
    }

    @Override
    public void add(String id, Embedding embedding) {
        insert(id, embedding, null);
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        String id = UUID.randomUUID().toString();
        insert(id, embedding, textSegment);
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return insertBatch(embeddings, null);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
        if (embedded != null && embeddings.size() != embedded.size()) {
            throw new IllegalArgumentException("embeddings/embedded size mismatch");
        }
        return insertBatch(embeddings, embedded);
    }

    /**
     * 批量入库：<strong>单连接 + 单条多行 INSERT</strong>（{@code VALUES (...),(...),...}），
     * 取代原来「每个 chunk 一条 INSERT + 一个新 Connection」（N 个 chunk = N 次建连 + N 次往返）。
     * 向量是 {@code ARRAY<FLOAT>} 字面量不能参数化，按数值内联（{@link #toArrayLiteral} 已 sanitize）；
     * id/text/metadata 仍走 PreparedStatement 占位防注入。
     */
    private List<String> insertBatch(List<Embedding> embeddings, List<TextSegment> embedded) {
        if (embeddings == null || embeddings.isEmpty()) {
            return List.of();
        }
        int n = embeddings.size();
        List<String> ids = new ArrayList<>(n);
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table)
                .append(" (id, text, metadata, embedding) VALUES ");
        for (int i = 0; i < n; i++) {
            ids.add(UUID.randomUUID().toString());
            if (i > 0) sql.append(',');
            sql.append("(?, ?, ?, ").append(toArrayLiteral(embeddings.get(i).vector())).append(')');
        }
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int p = 1;
            for (int i = 0; i < n; i++) {
                TextSegment seg = embedded == null ? null : embedded.get(i);
                ps.setString(p++, ids.get(i));
                ps.setString(p++, seg == null ? null : seg.text());
                ps.setString(p++, (seg == null || seg.metadata() == null) ? "{}" : writeJson(seg.metadata().toMap()));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Doris batch insert (" + n + " rows) failed", e);
        }
        return ids;
    }

    private void insert(String id, Embedding embedding, TextSegment segment) {
        String text = segment == null ? null : segment.text();
        String metaJson = (segment == null || segment.metadata() == null)
                ? "{}"
                : writeJson(segment.metadata().toMap());
        String vec = toArrayLiteral(embedding.vector());

        // ARRAY<FLOAT> literal cannot be parameterized; inline it after sanitizing as numeric.
        String sql = "INSERT INTO " + table + " (id, text, metadata, embedding) VALUES (?, ?, ?, " + vec + ")";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, text);
            ps.setString(3, metaJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Doris insert failed", e);
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        String distanceFn = "cosine".equals(metric) ? "cosine_distance_approximate" : "l2_distance_approximate";
        String vec = toArrayLiteral(request.queryEmbedding().vector());
        int limit = request.maxResults();

        DorisFilterTranslator.Translated where = new DorisFilterTranslator().translate(request.filter());
        String whereSql = where.whereClause.isEmpty() ? "" : " WHERE " + where.whereClause;

        String sql = "SELECT id, text, metadata, " + distanceFn + "(embedding, " + vec + ") AS dist FROM " + table
                + whereSql
                + " ORDER BY dist ASC LIMIT " + limit;

        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < where.params.size(); i++) {
                ps.setObject(i + 1, where.params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double dist = rs.getDouble("dist");
                    double score = distanceToScore(dist);
                    if (score < request.minScore()) {
                        continue;
                    }
                    String id = rs.getString("id");
                    String text = rs.getString("text");
                    String metaJson = rs.getString("metadata");
                    TextSegment seg = text == null ? null : TextSegment.from(text, parseMetadata(metaJson));
                    matches.add(new EmbeddingMatch<>(score, id, null, seg));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Doris search failed", e);
        }
        return new EmbeddingSearchResult<>(matches);
    }

    private double distanceToScore(double dist) {
        if ("cosine".equals(metric)) {
            // cosine_distance ∈ [0, 2] → similarity ∈ [0, 1]
            return Math.max(0.0, 1.0 - dist / 2.0);
        }
        // l2: smaller is better, map to (0, 1]
        return 1.0 / (1.0 + dist);
    }

    @Override
    public void remove(String id) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Doris delete failed", e);
        }
    }

    @Override
    public void removeAll(Collection<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = "DELETE FROM " + table + " WHERE id IN (" + placeholders + ")";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (String id : ids) {
                ps.setString(i++, id);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Doris bulk delete failed", e);
        }
    }

    @Override
    public void removeAll() {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE TABLE " + table);
        } catch (SQLException e) {
            throw new RuntimeException("Doris truncate failed", e);
        }
    }

    private static String toArrayLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private static String writeJson(Map<String, Object> m) {
        try {
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Metadata parseMetadata(String json) {
        if (json == null || json.isBlank()) return new Metadata();
        try {
            Map<String, Object> raw = JSON.readValue(json, new TypeReference<>() {});
            // Metadata only accepts String/Integer/Long/Float/Double/Boolean/UUID — coerce others to String
            Map<String, Object> safe = new HashMap<>(raw.size());
            raw.forEach((k, v) -> safe.put(k, v == null ? null : (isMetadataPrimitive(v) ? v : v.toString())));
            return new Metadata(safe);
        } catch (Exception e) {
            return new Metadata();
        }
    }

    private static boolean isMetadataPrimitive(Object v) {
        return v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof UUID;
    }
}
