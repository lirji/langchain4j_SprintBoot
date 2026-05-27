package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.rag.lifecycle.DocumentInfo;
import com.lrj.langchain4j.rag.lifecycle.DocumentService;
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
import java.nio.charset.StandardCharsets;
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
 */
@RestController
@RequestMapping("/rag/documents")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    /**
     * Multipart 上传。file 的原始 filename 作为 displayName，content-type 自动取。
     * MVP 只接受 text/* —— 二进制 PDF 等需要 LangChain4j 的 parser 模块，后续再加。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_ingest')")
    public ResponseEntity<DocumentInfo> uploadFile(@RequestPart("file") MultipartFile file,
                                                   @RequestParam(required = false) String category) throws IOException {
        if (file.isEmpty()) return ResponseEntity.badRequest().build();
        String displayName = file.getOriginalFilename();
        String contentType = file.getContentType();
        if (contentType != null && !contentType.startsWith("text/")) {
            return ResponseEntity.badRequest().header("X-Error",
                    "only text/* MIME supported; got " + contentType).build();
        }
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
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
