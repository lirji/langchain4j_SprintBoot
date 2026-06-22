package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.rag.lifecycle.DocumentInfo;
import com.lrj.langchain4j.rag.lifecycle.DocumentService;
import com.lrj.langchain4j.rag.lifecycle.MultimodalDocumentExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-tenant 文档 CRUD。所有 endpoint 都隐式按 {@code TenantContext.current()} 限制范围 ——
 * tenantA 看不到 tenantB 的文档（{@link DocumentService} 内部强制）。
 *
 * <p>支持两种上传方式：
 * <ul>
 *   <li>{@code multipart/form-data}：{@code POST /rag/documents} with file part {@code file}</li>
 *   <li>{@code application/json}：{@code POST /rag/documents} body {@code {title, text, contentType?, category?}}</li>
 * </ul>
 *
 * <p>multipart 路径支持<strong>多模态</strong>：上传图片（png/jpg…）时，若 {@code app.vision.enabled=true}，
 * 会用视觉模型把图像描述 + 文字 OCR 转写成文本再入库；其余格式仍走 Tika。见 {@link MultimodalDocumentExtractor}。
 */
@RestController
@RequestMapping("/rag/documents")
public class DocumentController {

    private final DocumentService documents;
    private final MultimodalDocumentExtractor extractor;
    /** 图片上传字节上限（仅对图片路径生效），与 {@code app.vision.max-image-bytes} 对齐。 */
    private final long maxImageBytes;

    public DocumentController(DocumentService documents,
                              MultimodalDocumentExtractor extractor,
                              @Value("${app.vision.max-image-bytes:10485760}") long maxImageBytes) {
        this.documents = documents;
        this.extractor = extractor;
        this.maxImageBytes = maxImageBytes;
    }

    /**
     * Multipart 上传。file 的原始 filename 作为 displayName，content-type 透传仅用于回显。
     * 正文交给 {@link MultimodalDocumentExtractor}：图片走视觉描述/OCR，其余格式交给 Apache Tika
     * （PDF / Word / Excel / PPT / HTML / 纯文本等，按内容嗅探类型不靠后缀）。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_ingest')")
    public ResponseEntity<DocumentInfo> uploadFile(@RequestPart("file") MultipartFile file,
                                                   @RequestParam(required = false) String category) throws IOException {
        if (file.isEmpty()) return ResponseEntity.badRequest().header("X-Error", "empty file").build();
        String displayName = file.getOriginalFilename();
        String contentType = file.getContentType();
        if (file.getSize() > maxImageBytes
                && MultimodalDocumentExtractor.isImage(contentType, displayName)) {
            return ResponseEntity.badRequest()
                    .header("X-Error", "image too large: " + file.getSize() + " > " + maxImageBytes + " bytes")
                    .build();
        }
        String text;
        try {
            text = extractor.extract(file.getBytes(), displayName, contentType);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().header("X-Error", e.getMessage()).build();
        }
        DocumentInfo info = documents.upload(displayName, contentType, text, category);
        return ResponseEntity.ok(info);
    }

    /** JSON 上传：{@code {title, text, contentType?, category?}}。脚本化场景更方便。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_ingest')")
    public ResponseEntity<DocumentInfo> uploadJson(@RequestBody Map<String, String> body) {
        String title = body.get("title");
        String text = body.get("text");
        String contentType = body.getOrDefault("contentType", "text/plain");
        String category = body.get("category");
        if (title == null || text == null) {
            return ResponseEntity.badRequest().header("X-Error", "title and text are required").build();
        }
        DocumentInfo info = documents.upload(title, contentType, text, category);
        return ResponseEntity.ok(info);
    }

    @GetMapping
    public List<DocumentInfo> list() {
        return documents.list();
    }

    @GetMapping("/{docId}")
    public ResponseEntity<DocumentInfo> get(@PathVariable String docId) {
        Optional<DocumentInfo> info = documents.get(docId);
        return info.<ResponseEntity<DocumentInfo>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{docId}")
    @PreAuthorize("hasAuthority('SCOPE_ingest')")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String docId) {
        boolean removed = documents.delete(docId);
        if (!removed) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("docId", docId, "deleted", true));
    }
}
