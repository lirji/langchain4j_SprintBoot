package com.lrj.langchain4j.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 Markdown 标题切分：每个 {@code ##}（或更深层）section 一个 segment。
 * Section 超过 {@code maxCharsPerSection} 时 fallback 到传入的 splitter（一般是 recursive）。
 *
 * <p>对照默认 {@code DocumentSplitters.recursive(300, 50)} —— 按字符数硬切，
 * 经常把同一 section 切断、跨 section 拼到一起。Markdown header 切分让每个 chunk
 * 对应一个完整主题，{@code [doc=file#N]} 引用也更有意义。
 *
 * <p>metadata 注入：
 * <ul>
 *   <li>{@code index} — section 顺序号（0-based），覆盖父 Document 的 index（如有）</li>
 *   <li>{@code section} — section 标题（去掉 {@code ##} 前缀），方便人工排查检索结果</li>
 * </ul>
 *
 * <p>非 Markdown 文档（不含 {@code ##} 行）只会产生 1 个大 segment，建议这种情况配 recursive 策略
 * —— 或者长 section 会被 fallback 拆开，行为退化成 recursive。
 */
public class MarkdownHeaderSplitter implements DocumentSplitter {

    private final int maxCharsPerSection;
    private final DocumentSplitter fallbackForLongSection;

    public MarkdownHeaderSplitter(int maxCharsPerSection, DocumentSplitter fallbackForLongSection) {
        if (maxCharsPerSection <= 0) {
            throw new IllegalArgumentException("maxCharsPerSection must be > 0");
        }
        this.maxCharsPerSection = maxCharsPerSection;
        this.fallbackForLongSection = fallbackForLongSection;
    }

    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        Metadata baseMeta = document.metadata();

        // (?m) multiline + lookahead 保留 heading 在当前 section 开头
        // ^##+ \s 匹配 ## / ### / #### 等任意深度（## 是文档章节的最小单位，单 # 是文档 H1 通常只一行）
        String[] sections = text.split("(?m)(?=^##+ )");

        List<TextSegment> out = new ArrayList<>();
        int idx = 0;
        for (String raw : sections) {
            String section = raw.strip();
            if (section.isEmpty()) continue;

            Metadata meta = baseMeta.copy();
            meta.put("index", String.valueOf(idx));
            String title = extractTitle(section);
            if (title != null) meta.put("section", title);

            if (section.length() <= maxCharsPerSection || fallbackForLongSection == null) {
                out.add(TextSegment.from(section, meta));
            } else {
                // section 太长 → 用 fallback 在 section 内部再切，沿用 section 的 metadata
                Document sub = Document.from(section, meta);
                out.addAll(fallbackForLongSection.split(sub));
            }
            idx++;
        }
        return out;
    }

    private static String extractTitle(String section) {
        int nl = section.indexOf('\n');
        String firstLine = nl < 0 ? section : section.substring(0, nl);
        // 去掉前导 ##/###/...
        return firstLine.replaceFirst("^#+\\s+", "").strip();
    }
}
