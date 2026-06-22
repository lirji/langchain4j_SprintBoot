package com.lrj.langchain4j.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 按 Markdown 标题切分：每个 section 一个 segment。
 * Section 超过 {@code maxSizePerSection} 时 fallback 到传入的 splitter（一般是 recursive）。
 *
 * <p>对照默认 {@code DocumentSplitters.recursive(300, 50)} —— 按字符数硬切，
 * 经常把同一 section 切断、跨 section 拼到一起。Markdown header 切分让每个 chunk
 * 对应一个完整主题，{@code [doc=file#N]} 引用也更有意义。
 *
 * <p><strong>自适应标题层级</strong>：优先按 {@code ##}（或更深）切；若文档里压根没有 {@code ##+}
 * 行、只有 {@code #}（H1）分级，则退而按 {@code #} 切 —— 否则纯 {@code #} 文档会一刀不切成一个巨型
 * segment。两者都没有（非 markdown）则整篇 1 个 segment。
 *
 * <p><strong>极小 section 合并</strong>（{@code minSizePerSection > 0} 时启用）：把连续的、单独看不足
 * {@code minSizePerSection} 的小 section 向后并成一块，直到够大。避免「{@code ## 标题} + 一行」这种碎块
 * 单独入库污染检索。尾部不足的残余并进上一块。{@code minSizePerSection == 0}（默认）时关闭，行为与历史一致。
 *
 * <p>section 长度阈值（{@code maxSizePerSection} / {@code minSizePerSection}）的计量单位由
 * {@code tokenEstimator} 决定：
 * <ul>
 *   <li>{@code null}（默认）— 按字符数（{@code section.length()}），与历史行为一致</li>
 *   <li>非 {@code null} — 按 token 数（{@code estimateTokenCountInText}），与 token 模式的 recursive
 *       fallback 单位对齐，避免「阈值数 token、切分按 char」自相矛盾</li>
 * </ul>
 *
 * <p>metadata 注入：
 * <ul>
 *   <li>{@code index} — section 顺序号（0-based），覆盖父 Document 的 index（如有）</li>
 *   <li>{@code section} — 当前 chunk 主标题（去掉 {@code #} 前缀的叶子标题），方便人工排查检索结果</li>
 *   <li>{@code breadcrumb} — 标题层级路径（如 {@code Top > Sub > SubSub}），仅深度 > 1 时注入。
 *       让一个 {@code ###} chunk 带上父 {@code ##}/{@code #} 的上下文，检索适配度更高</li>
 * </ul>
 */
public class MarkdownHeaderSplitter implements DocumentSplitter {

    /** 文档里是否存在 {@code ##}+ 标题行。 */
    private static final Pattern H2_PLUS = Pattern.compile("(?m)^##+ ");
    /** 文档里是否存在 {@code #} 标题行。 */
    private static final Pattern H1 = Pattern.compile("(?m)^# ");

    private final int maxSizePerSection;
    private final int minSizePerSection;
    private final DocumentSplitter fallbackForLongSection;
    private final TokenCountEstimator tokenEstimator;

    /** 字符计量、不合并（向后兼容）。 */
    public MarkdownHeaderSplitter(int maxSizePerSection, DocumentSplitter fallbackForLongSection) {
        this(maxSizePerSection, fallbackForLongSection, null, 0);
    }

    /** 指定计量单位、不合并（向后兼容）。 */
    public MarkdownHeaderSplitter(int maxSizePerSection, DocumentSplitter fallbackForLongSection,
                                  TokenCountEstimator tokenEstimator) {
        this(maxSizePerSection, fallbackForLongSection, tokenEstimator, 0);
    }

    /**
     * @param tokenEstimator    非 null 时阈值按 token 计量；null 时按字符计量
     * @param minSizePerSection 极小 section 合并阈值（同 tokenEstimator 单位）；0 = 关闭合并
     */
    public MarkdownHeaderSplitter(int maxSizePerSection, DocumentSplitter fallbackForLongSection,
                                  TokenCountEstimator tokenEstimator, int minSizePerSection) {
        if (maxSizePerSection <= 0) {
            throw new IllegalArgumentException("maxSizePerSection must be > 0");
        }
        if (minSizePerSection < 0) {
            throw new IllegalArgumentException("minSizePerSection must be >= 0");
        }
        this.maxSizePerSection = maxSizePerSection;
        this.minSizePerSection = minSizePerSection;
        this.fallbackForLongSection = fallbackForLongSection;
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        Metadata baseMeta = document.metadata();

        // 自适应：有 ##+ 按 ##+ 切（历史行为）；否则只有 # 就按 # 切；都没有就整篇一段。
        String boundary = chooseBoundary(text);
        String[] rawSections = (boundary == null) ? new String[]{text} : text.split(boundary);

        List<String> sections = new ArrayList<>();
        for (String raw : rawSections) {
            String s = raw.strip();
            if (!s.isEmpty()) sections.add(s);
        }
        if (minSizePerSection > 0 && sections.size() > 1) {
            sections = mergeTiny(sections);
        }

        Deque<Heading> stack = new ArrayDeque<>();
        List<TextSegment> out = new ArrayList<>();
        int idx = 0;
        for (String section : sections) {
            Metadata meta = baseMeta.copy();
            meta.put("index", String.valueOf(idx));
            String title = extractTitle(section);
            if (title != null) meta.put("section", title);
            String breadcrumb = pushAndBreadcrumb(stack, headingLevel(section), title);
            if (breadcrumb != null && breadcrumb.contains(" > ")) {
                meta.put("breadcrumb", breadcrumb);
            }

            if (sectionSize(section) <= maxSizePerSection || fallbackForLongSection == null) {
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

    /** 选择切分边界：{@code ##+} 优先，否则 {@code #}，再否则不切（返回 null）。 */
    private static String chooseBoundary(String text) {
        if (H2_PLUS.matcher(text).find()) return "(?m)(?=^##+ )";
        if (H1.matcher(text).find()) return "(?m)(?=^#+ )";
        return null;
    }

    /** 把连续的小 section 向后合并，直到累计 ≥ minSizePerSection；尾部残余并进上一块。 */
    private List<String> mergeTiny(List<String> sections) {
        List<String> merged = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String s : sections) {
            if (buf.length() > 0) buf.append("\n\n");
            buf.append(s);
            if (sectionSize(buf.toString()) >= minSizePerSection) {
                merged.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            if (!merged.isEmpty()) {
                merged.set(merged.size() - 1, merged.get(merged.size() - 1) + "\n\n" + buf);
            } else {
                merged.add(buf.toString());
            }
        }
        return merged;
    }

    /** section 长度：有 estimator 按 token 数，否则按字符数。 */
    private int sectionSize(String section) {
        return tokenEstimator == null
                ? section.length()
                : tokenEstimator.estimateTokenCountInText(section);
    }

    private static String extractTitle(String section) {
        int nl = section.indexOf('\n');
        String firstLine = nl < 0 ? section : section.substring(0, nl);
        // 去掉前导 #/##/...
        return firstLine.replaceFirst("^#+\\s+", "").strip();
    }

    /** 首行的标题层级（前导 {@code #} 个数，须后跟空格）；非标题行返回 0。 */
    private static int headingLevel(String section) {
        int i = 0;
        while (i < section.length() && section.charAt(i) == '#') i++;
        return (i > 0 && i < section.length() && section.charAt(i) == ' ') ? i : 0;
    }

    /** 维护标题栈并返回当前 chunk 的层级路径；无标题（level 0）返回 null。 */
    private static String pushAndBreadcrumb(Deque<Heading> stack, int level, String title) {
        if (level == 0 || title == null) return null;
        while (!stack.isEmpty() && stack.peekLast().level >= level) stack.removeLast();
        stack.addLast(new Heading(level, title));
        return stack.stream().map(h -> h.title).collect(Collectors.joining(" > "));
    }

    private record Heading(int level, String title) {}
}
