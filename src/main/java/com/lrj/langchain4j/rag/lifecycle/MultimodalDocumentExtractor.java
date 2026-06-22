package com.lrj.langchain4j.rag.lifecycle;

import com.lrj.langchain4j.ai.vision.VisionContentGuard;
import com.lrj.langchain4j.ai.vision.VisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Set;

/**
 * 多模态上传正文抽取的<strong>统一入口</strong>，包在 {@link DocumentTextExtractor}（Tika 纯文本）
 * 之外：
 * <ul>
 *   <li><strong>图片</strong>（png/jpg/gif/webp/bmp/tiff…）→ 路由到 {@link VisionModel#caption}，
 *       用视觉模型产出「图像描述 + 可见文字 OCR 转写」的文本（同一调用覆盖图像理解与 OCR）；</li>
 *   <li><strong>其余</strong>（PDF/Word/Excel/PPT/HTML/纯文本）→ 仍走 Tika，行为与历史完全一致。</li>
 * </ul>
 *
 * <p>产出的文本回到 {@code DocumentService.upload(...)}，下游 chunk→embed→检索→引用全链不变——
 * 图片入库后就能像普通文档一样被 RAG 检索、被 {@code [doc=图片名#片段号]} 引用。
 *
 * <p>{@link VisionModel} 是<strong>软依赖</strong>（{@code app.vision.enabled=false} 时 Bean 不存在
 * → {@code getIfAvailable()} 返 null）。此时上传图片会得到清晰的 400 提示而非 NPE；上传文本类
 * 文档完全不受影响。
 */
@Component
public class MultimodalDocumentExtractor {

    private static final Logger log = LoggerFactory.getLogger(MultimodalDocumentExtractor.class);

    /** 按扩展名兜底识别图片（content-type 缺失或为 application/octet-stream 时）。 */
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff");

    private final DocumentTextExtractor tika;
    private final ObjectProvider<VisionModel> visionProvider;
    private final VisionContentGuard visionGuard;

    public MultimodalDocumentExtractor(DocumentTextExtractor tika,
                                       ObjectProvider<VisionModel> visionProvider,
                                       VisionContentGuard visionGuard) {
        this.tika = tika;
        this.visionProvider = visionProvider;
        this.visionGuard = visionGuard;
    }

    /**
     * @param bytes       上传文件原始字节
     * @param filename    原始文件名（用于扩展名识别 + 报错）
     * @param contentType 浏览器/客户端给的 MIME（可能为 null / 不准，扩展名兜底）
     * @return 抽取出的正文（图片走视觉描述，其余走 Tika）
     * @throws IllegalArgumentException 图片但 vision 未启用 / 解析失败 / 正文为空（controller 翻 400）
     */
    public String extract(byte[] bytes, String filename, String contentType) {
        if (isImage(contentType, filename)) {
            VisionModel vision = visionProvider.getIfAvailable();
            if (vision == null) {
                throw new IllegalArgumentException(
                        "image upload requires multimodal support; set app.vision.enabled=true to enable it");
            }
            log.info("multimodal extract: '{}' (type={}) -> vision caption/OCR", filename, contentType);
            String text = vision.caption(bytes, resolveImageMime(contentType, filename));
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("vision model returned empty description for '" + filename + "'");
            }
            // 入库前安全闸：注入指令 → 阻断，PII → 脱敏（图像 caption/OCR 是不可信外部输入）
            return visionGuard.sanitizeForIngest(text.trim(), filename);
        }
        return tika.extract(new ByteArrayInputStream(bytes), filename);
    }

    /** content-type 以 {@code image/} 开头，或扩展名命中图片白名单。 */
    public static boolean isImage(String contentType, String filename) {
        if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
            return true;
        }
        return IMAGE_EXTENSIONS.contains(extension(filename));
    }

    /** 优先用 image/* 的 content-type；否则按扩展名推 MIME；都没有兜底 image/png。 */
    public static String resolveImageMime(String contentType, String filename) {
        if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
            return contentType.toLowerCase();
        }
        return switch (extension(filename)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            default -> "image/png";
        };
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase();
    }
}
