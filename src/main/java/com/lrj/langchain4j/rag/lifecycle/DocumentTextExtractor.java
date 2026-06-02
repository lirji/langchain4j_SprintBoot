package com.lrj.langchain4j.rag.lifecycle;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * 把上传的二进制文档（PDF / Word / Excel / PPT / HTML / 纯文本…）抽成纯文本。
 *
 * <p>背后是 Apache Tika（{@link ApacheTikaDocumentParser}），一个 parser 吃几乎所有常见格式，
 * 自动按 magic bytes 嗅探类型，不依赖 filename 后缀。无状态线程安全，整个应用共用一个实例。
 *
 * <p>跟 {@code RagIngestionService}（文件夹批量入库走 {@code FileSystemDocumentLoader} +
 * easy-rag 的 Tika SPI 默认 parser）能力一致 —— 这里只是把同样的解析能力补给 per-tenant 的
 * {@code POST /rag/documents} 上传路径（之前它手动 UTF-8 解码、只收 {@code text/*}）。
 */
@Component
public class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);

    private final ApacheTikaDocumentParser parser = new ApacheTikaDocumentParser();

    /**
     * 解析输入流为纯文本。
     *
     * @param in       文档字节流（调用方负责关闭，如 {@code MultipartFile.getInputStream()}）
     * @param filename 原始文件名，仅用于日志/报错（Tika 靠内容嗅探类型，不靠后缀）
     * @return 抽取出的正文，已 trim
     * @throws IllegalArgumentException 解析失败或正文为空（controller 翻成 400）
     */
    public String extract(InputStream in, String filename) {
        try {
            Document doc = parser.parse(in);
            String text = doc.text();
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("no extractable text in '" + filename + "'");
            }
            return text.trim();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // Tika 对加密 PDF / 损坏文件 / 不支持的格式会抛各种 RuntimeException（含 BlankDocumentException）
            log.warn("failed to parse uploaded document '{}': {}", filename, e.toString());
            throw new IllegalArgumentException("failed to parse '" + filename + "': " + e.getMessage(), e);
        }
    }
}
