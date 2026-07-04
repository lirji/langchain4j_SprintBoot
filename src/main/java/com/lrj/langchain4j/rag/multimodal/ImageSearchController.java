package com.lrj.langchain4j.rag.multimodal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 原生多模态检索入口（{@code app.rag.multimodal-embedding.enabled=true} 才映射）。走与 {@code /chat}
 * 同一套鉴权链（{@code X-Api-Key} + 多租户 + 限流 + 配额）——落在默认限流族。
 *
 * <ul>
 *   <li>{@code POST /rag/image} — multipart 上传图片，原生 embed 入库（type=image）</li>
 *   <li>{@code POST /rag/image-search} — 文本 query 检索图片（text→image）</li>
 * </ul>
 */
@RestController
@ConditionalOnProperty(name = "app.rag.multimodal-embedding.enabled", havingValue = "true")
public class ImageSearchController {

    private final MultimodalRetrievalService retrieval;
    private final MultimodalEmbeddingProperties props;

    public ImageSearchController(MultimodalRetrievalService retrieval, MultimodalEmbeddingProperties props) {
        this.retrieval = retrieval;
        this.props = props;
    }

    /** 图片入库：multipart {@code image}。返回 {@code {id, fileName}}。 */
    @PostMapping(value = "/rag/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> ingest(@RequestPart("image") MultipartFile image) throws IOException {
        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "empty image"));
        }
        if (image.getSize() > props.getMaxImageBytes()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "image too large: " + image.getSize() + " > " + props.getMaxImageBytes() + " bytes"));
        }
        String fileName = image.getOriginalFilename();
        try {
            String id = retrieval.ingestImage(image.getBytes(), image.getContentType(), fileName);
            return ResponseEntity.ok(Map.of("id", id, "fileName", fileName == null ? "" : fileName));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** text→image：body {@code {query, topK?, minScore?}} → {@code {query, results:[{id,fileName,score}]}}。 */
    @PostMapping(value = "/rag/image-search",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> search(@RequestBody SearchRequest body) {
        if (body == null || body.query() == null || body.query().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "empty query"));
        }
        int topK = body.topK() == null ? 0 : body.topK();
        double minScore = body.minScore() == null ? -1 : body.minScore();
        List<MultimodalRetrievalService.ImageMatch> results =
                retrieval.searchByText(body.query(), topK, minScore);
        return ResponseEntity.ok(Map.of("query", body.query(), "results", results));
    }

    /** query 支持 form 覆盖（可选）—— 仅用于 JSON body 的字段绑定。 */
    public record SearchRequest(String query, Integer topK, Double minScore) {}
}
