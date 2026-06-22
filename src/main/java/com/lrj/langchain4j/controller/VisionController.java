package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.ai.vision.VisionModel;
import com.lrj.langchain4j.ai.vision.VisionProperties;
import com.lrj.langchain4j.rag.lifecycle.MultimodalDocumentExtractor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 视觉对话入口（{@code app.vision.enabled=true} 才映射）。走与 {@code /chat} 同一套鉴权链
 * （{@code X-Api-Key} + 多租户 + 限流 + 配额；落在 "chat" 限流族）。
 *
 * <p>与文档入库（{@code POST /rag/documents} 图片上传）的区别：这里<strong>看图直接作答、不入库</strong>，
 * 单轮、不带 ChatMemory——语义清晰、可重复。要把图沉淀进知识库走文档上传路径。
 */
@RestController
@ConditionalOnProperty(name = "app.vision.enabled", havingValue = "true")
public class VisionController {

    private final VisionModel vision;
    private final VisionProperties props;

    public VisionController(VisionModel vision, VisionProperties props) {
        this.vision = vision;
        this.props = props;
    }

    /**
     * 看图作答：multipart {@code image} + {@code message}（问题，可选；留空则默认描述+转写）。
     * 返回 {@code {reply}}。
     */
    @PostMapping(value = "/chat/vision", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> chat(@RequestPart("image") MultipartFile image,
                                  @RequestParam(required = false) String message) throws IOException {
        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "empty image"));
        }
        if (image.getSize() > props.getMaxImageBytes()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "image too large: " + image.getSize() + " > " + props.getMaxImageBytes() + " bytes"));
        }
        String mime = MultimodalDocumentExtractor.resolveImageMime(
                image.getContentType(), image.getOriginalFilename());
        try {
            String reply = vision.answer(image.getBytes(), mime, message);
            return ResponseEntity.ok(Map.of("reply", reply));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
