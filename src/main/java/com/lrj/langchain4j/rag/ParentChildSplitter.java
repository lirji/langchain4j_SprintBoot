package com.lrj.langchain4j.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parent-child（又叫 small-to-big）切分：用<strong>小块</strong>去 embed 保证召回精准，
 * 命中后<strong>换成它所属的大块（parent）</strong>喂给模型保证上下文完整。
 *
 * <p>解决向量召回的两难：chunk 切小 → 命中精准但上下文残缺（"它支持..."不知道主语）；
 * chunk 切大 → 上下文够但一个块塞多个主题、相似度被稀释召不准。parent-child 把这两件事拆开：
 * <ul>
 *   <li><strong>child</strong>（被 embed 的）：用 {@code parentSplitter} 切出的每个 parent 再用
 *       {@code childSplitter} 切成的小块，{@code child.text()} 是真正进向量库的文本 → 召回精度由小块决定</li>
 *   <li><strong>parent</strong>（被喂给 LLM 的）：每个 child 的 metadata 里挂上所属 parent 的
 *       {@link #PARENT_ID} 与全文 {@link #PARENT_TEXT}；检索命中 child 后，
 *       {@link TaggedSourceContentInjector} 读 {@code parent_text} 换进 prompt（多个 child 命中同一
 *       parent 自动去重，parent 只注入一次）→ 上下文完整由大块决定</li>
 * </ul>
 *
 * <p><strong>存储取舍</strong>：parent 全文随每个 child 冗余存进 metadata（而非另起 parent store）。
 * 换来零新 Bean / 零生命周期同步 / 重启安全（metadata 落进任意 EmbeddingStore，6 种后端一致）/
 * 跨租户随 child 的 {@code tenantId} 天然隔离。代价是 store 体积膨胀（一个 parent 文本被它的 N 个
 * child 各存一份）—— 与 {@code DocumentMirror} 同款"小规模够用、超大语料换外部存储"的定位。
 *
 * <p>{@code parentSplitter} 可以是 {@link MarkdownHeaderSplitter}（section 作 parent，story 最强）
 * 或 recursive（通用）；{@code childSplitter} 一般是 recursive 小窗口。两者都由
 * {@link DocumentSplitterFactory} 按 {@code app.rag.chunking.*} 装配。
 */
public class ParentChildSplitter implements DocumentSplitter {

    /** child metadata key：所属 parent 在本文档内的稳定序号（0-based）。 */
    public static final String PARENT_ID = "parent_id";
    /** child metadata key：所属 parent 的完整文本（注入时换给模型）。 */
    public static final String PARENT_TEXT = "parent_text";

    private final DocumentSplitter parentSplitter;
    private final DocumentSplitter childSplitter;

    public ParentChildSplitter(DocumentSplitter parentSplitter, DocumentSplitter childSplitter) {
        this.parentSplitter = Objects.requireNonNull(parentSplitter, "parentSplitter");
        this.childSplitter = Objects.requireNonNull(childSplitter, "childSplitter");
    }

    @Override
    public List<TextSegment> split(Document document) {
        List<TextSegment> parents = parentSplitter.split(document);
        List<TextSegment> children = new ArrayList<>();
        int parentIdx = 0;
        for (TextSegment parent : parents) {
            String parentId = String.valueOf(parentIdx);
            // 在 parent 文本上再切 child，沿用 parent 的 metadata（含 tenantId / category / file_name…）
            List<TextSegment> kids = childSplitter.split(Document.from(parent.text(), parent.metadata()));
            if (kids.isEmpty()) {
                // 兜底：parent 没切出任何 child（极短文本）时，parent 自己当唯一 child
                kids = List.of(parent);
            }
            for (TextSegment kid : kids) {
                Metadata meta = kid.metadata().copy();
                meta.put(PARENT_ID, parentId);
                meta.put(PARENT_TEXT, parent.text());
                children.add(TextSegment.from(kid.text(), meta));
            }
            parentIdx++;
        }
        return children;
    }
}
