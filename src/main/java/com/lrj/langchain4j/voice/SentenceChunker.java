package com.lrj.langchain4j.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * 把流式 LLM token 攒成「整句」，供 SSE 半流式语音边生成边合成（每凑够一句就 TTS 推回）。
 *
 * <p>切句规则：遇到句末标点（。！？.!?…换行）且累计长度 ≥ {@code minChars} 时切出一句——
 * {@code minChars} 防止「好。」这种过短句各自单独 TTS（既费调用又听感碎）。{@link #flush()} 取尾巴
 * （不足一句的残余，收口时合成）。纯状态机、无 I/O，确定性可单测。
 */
public class SentenceChunker {

    private static final String ENDERS = "。！？!?…\n";

    private final StringBuilder buf = new StringBuilder();
    private final int minChars;

    public SentenceChunker(int minChars) {
        this.minChars = Math.max(1, minChars);
    }

    /** 喂一个 token，返回本次因此凑齐的完整句子（可能 0~多句）。 */
    public List<String> feed(String token) {
        List<String> out = new ArrayList<>();
        if (token == null || token.isEmpty()) return out;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            buf.append(c);
            if (ENDERS.indexOf(c) >= 0 && buf.toString().strip().length() >= minChars) {
                String s = buf.toString().strip();
                if (!s.isEmpty()) out.add(s);
                buf.setLength(0);
            }
        }
        return out;
    }

    /** 取走剩余不足一句的残余（收口时合成最后一段），并清空。 */
    public String flush() {
        String s = buf.toString().strip();
        buf.setLength(0);
        return s;
    }
}
