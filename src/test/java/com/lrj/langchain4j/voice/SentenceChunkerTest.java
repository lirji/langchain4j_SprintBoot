package com.lrj.langchain4j.voice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 分句器确定性单测：逐 token 累积切句 / minChars 阈值 / flush 残余 / 引用标记不误切。 */
class SentenceChunkerTest {

    /** 把整段文本拆成单字 token 喂进去，模拟流式。 */
    private static List<String> stream(SentenceChunker c, String text) {
        List<String> all = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) all.addAll(c.feed(String.valueOf(text.charAt(i))));
        return all;
    }

    @Test
    void splitsOnSentenceEnders() {
        SentenceChunker c = new SentenceChunker(2);
        List<String> done = stream(c, "你好。今天天气不错！你说呢？");
        assertThat(done).containsExactly("你好。", "今天天气不错！", "你说呢？");
        assertThat(c.flush()).isEmpty();
    }

    @Test
    void shortFragmentBelowMinChars_notSplitAlone() {
        // minChars=5：「好。」太短不单独成句，继续并入后文
        SentenceChunker c = new SentenceChunker(5);
        List<String> done = stream(c, "好。我帮您查一下。");
        assertThat(done).containsExactly("好。我帮您查一下。");
    }

    @Test
    void flush_returnsTrailingPartial() {
        SentenceChunker c = new SentenceChunker(2);
        List<String> done = stream(c, "第一句。还没说完的尾巴");
        assertThat(done).containsExactly("第一句。");
        assertThat(c.flush()).isEqualTo("还没说完的尾巴");
        assertThat(c.flush()).isEmpty();   // 取走后清空
    }

    @Test
    void citationMarkersDoNotSplit() {
        // [doc=file#3] 里没有句末标点，不会被误切
        SentenceChunker c = new SentenceChunker(2);
        List<String> done = stream(c, "见资料[doc=faq#3]说明。");
        assertThat(done).containsExactly("见资料[doc=faq#3]说明。");
    }

    @Test
    void multiCharToken_yieldsMultipleSentences() {
        SentenceChunker c = new SentenceChunker(2);
        // 一个 token 里含多句
        assertThat(c.feed("一。二。三")).containsExactly("一。", "二。");
        assertThat(c.flush()).isEqualTo("三");
    }
}
