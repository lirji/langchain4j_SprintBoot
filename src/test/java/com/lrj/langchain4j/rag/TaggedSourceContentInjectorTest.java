package com.lrj.langchain4j.rag;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注入侧的 parent-child 闭环：命中 child 但喂给模型的是 parent 全文，且同一 parent 去重只注入一次；
 * 非 parent-child 场景行为不变（注入 segment 自身文本）。
 */
class TaggedSourceContentInjectorTest {

    private final TaggedSourceContentInjector injector = new TaggedSourceContentInjector();

    @AfterEach
    void clearThreadLocal() {
        RetrievedSourcesContext.clear();
    }

    private static Content child(String childText, String parentId, String parentText) {
        Metadata m = new Metadata();
        m.put("file_name", "guide.md");
        m.put(ParentChildSplitter.PARENT_ID, parentId);
        m.put(ParentChildSplitter.PARENT_TEXT, parentText);
        return Content.from(TextSegment.from(childText, m));
    }

    @Test
    void injectsParentTextNotChildText() {
        Content c = child("小块命中片段", "0", "这是完整 parent 段落，含小块命中片段以及上下文。");

        UserMessage out = (UserMessage) injector.inject(List.of(c), UserMessage.from("问题"));

        String text = out.singleText();
        assertThat(text).contains("这是完整 parent 段落");      // parent 全文进了 prompt
        assertThat(text).contains("<source id=\"guide.md#0\">"); // id 用 parent_id
        // 暴露给 grounding 的 source 也是 parent 文本
        List<RetrievedSourcesContext.Source> sources = RetrievedSourcesContext.get();
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).id()).isEqualTo("guide.md#0");
        assertThat(sources.get(0).text()).startsWith("这是完整 parent 段落");
    }

    @Test
    void dedupesMultipleChildrenOfSameParent() {
        String parentText = "同一个 parent 段落，被切成了两个小块 A 和 B。";
        Content a = child("小块 A", "0", parentText);
        Content b = child("小块 B", "0", parentText);

        UserMessage out = (UserMessage) injector.inject(List.of(a, b), UserMessage.from("问题"));

        String text = out.singleText();
        // parent 文本只出现一次（去重），不会因为两个 child 命中而重复塞两遍
        assertThat(text.split(java.util.regex.Pattern.quote(parentText), -1)).hasSize(2); // 1 次出现 → split 成 2 段
        assertThat(RetrievedSourcesContext.get()).hasSize(1);
    }

    @Test
    void nonParentChildSegmentUsesOwnTextAndIndex() {
        Metadata m = new Metadata();
        m.put("file_name", "notes.md");
        m.put("index", "3");
        Content c = Content.from(TextSegment.from("普通片段文本", m));

        UserMessage out = (UserMessage) injector.inject(List.of(c), UserMessage.from("问题"));

        String text = out.singleText();
        assertThat(text).contains("普通片段文本");
        assertThat(text).contains("<source id=\"notes.md#3\">");
        assertThat(RetrievedSourcesContext.get()).hasSize(1);
        assertThat(RetrievedSourcesContext.get().get(0).id()).isEqualTo("notes.md#3");
    }
}
