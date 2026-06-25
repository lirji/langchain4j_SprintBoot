package com.lrj.langchain4j.rag;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把检索到的 Content 用 {@code <source id="...">...</source>} 标签包起来再拼到用户消息后，
 * 给模型一个可在回答中**字面**引用的稳定 id（来源于 {@link TextSegment} 的 metadata.file_name +
 * chunk index）。配合 {@code AssistantProperties.citationPolicy} 里要求的
 * {@code [doc=文件名#片段号]} 引用格式形成闭环 —— 没有这一层注入，模型即便想引用也没有可引的 id。
 *
 * <p>替代 LangChain4j 内置的 {@code DefaultContentInjector}（默认把片段用换行拼起来，
 * 模型只能看到文本却看不到来源元信息）。
 */
public class TaggedSourceContentInjector implements ContentInjector {

    @Override
    public ChatMessage inject(List<Content> contents, ChatMessage chatMessage) {
        if (contents == null || contents.isEmpty()) {
            return chatMessage;
        }
        // 只处理 UserMessage —— 别的消息类型不该插检索片段
        if (!(chatMessage instanceof UserMessage userMessage)) {
            return chatMessage;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(userMessage.singleText());
        sb.append("\n\n[Retrieved sources — cite the ones you actually use as `[doc=ID]`]:\n");
        List<RetrievedSourcesContext.Source> collected = new ArrayList<>(contents.size());
        // parent-child：多个 child 命中同一 parent → 按 id 去重，parent 文本只注入一次。
        // 非 parent-child 场景 id 天然各异（file#index），去重不会误合并不同 chunk。
        Set<String> seenIds = new HashSet<>(contents.size());
        for (int i = 0; i < contents.size(); i++) {
            TextSegment seg = contents.get(i).textSegment();
            String id = inferId(seg, i);
            if (!seenIds.add(id)) {
                continue;
            }
            // parent-child：命中的是小 child，但喂给模型的是它所属的 parent 全文（上下文完整）
            String body = sourceBody(seg);
            collected.add(new RetrievedSourcesContext.Source(id, body));
            sb.append("<source id=\"").append(id).append("\">\n");
            sb.append(body).append("\n");
            sb.append("</source>\n");
        }
        // 暴露给 grounding 后校验（Layer 0 引用核对 + Layer 1 faithfulness）。调用方负责 clear。
        RetrievedSourcesContext.set(collected);
        return UserMessage.from(sb.toString());
    }

    /**
     * 生成稳定 id。优先用 metadata 里的 {@code file_name}（FileSystemDocumentLoader 默认放这个 key），
     * 退到 {@code source} / {@code absolute_directory_path} 文件名，再退到 "doc"。
     * chunk 标号：parent-child 模式优先用 {@code parent_id}（让同一 parent 的多个 child 共享同一引用 id、
     * 与注入的 parent 文本对齐），否则用 {@code index}（部分 splitter 会放），再退到列表顺序号。
     */
    public static String inferId(TextSegment seg, int fallbackIndex) {
        var meta = seg.metadata();
        String name = firstNonBlank(
                meta.getString("file_name"),
                meta.getString("source"),
                lastPathSegment(meta.getString("absolute_directory_path")),
                "doc");
        String idx = firstNonBlank(
                meta.getString(ParentChildSplitter.PARENT_ID),
                meta.getString("index"));
        if (idx == null || idx.isBlank() || "doc".equals(idx)) {
            idx = String.valueOf(fallbackIndex);
        }
        return name + "#" + idx;
    }

    /**
     * 注入给模型的 source 正文：parent-child 模式下返回所属 parent 全文（{@link ParentChildSplitter#PARENT_TEXT}），
     * 否则返回 segment 自身文本。这样召回精度由小 child 决定、喂给 LLM 的上下文由大 parent 决定。
     */
    private static String sourceBody(TextSegment seg) {
        String parentText = seg.metadata().getString(ParentChildSplitter.PARENT_TEXT);
        return (parentText != null && !parentText.isBlank()) ? parentText : seg.text();
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return "doc";
    }

    private static String lastPathSegment(String path) {
        if (path == null || path.isBlank()) return null;
        int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return sep >= 0 && sep < path.length() - 1 ? path.substring(sep + 1) : path;
    }
}
