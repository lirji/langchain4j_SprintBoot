package com.lrj.langchain4j.ai.cascade;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 置信判定：决定便宜模型的答案是否**够用**，不够用就升级到强模型。
 *
 * <p>纯确定性启发式（无 LLM，可单测）：
 * <ol>
 *   <li>空 / 过短（{@code < min-answer-chars}）→ 低置信；</li>
 *   <li>命中不确定 / 拒答标记（{@code uncertainty-markers}）→ 低置信。</li>
 * </ol>
 *
 * <p>可选增强（{@code app.llm.cascade.self-rating=true}）：启发式通过后再让便宜模型对自己答案
 * temp=0 自评一个 0–1 分，低于 {@code confidence-threshold} 也判低置信。{@code rater} 为 null
 * （未开自评）时跳过此步。自评走 {@code buildJudgeChatModel} 出来的 temp=0 模型，
 * <strong>不是</strong>注册的 ChatModel Bean。
 */
public class ConfidenceGate {

    private static final Logger log = LoggerFactory.getLogger(ConfidenceGate.class);

    /** 从自评回复里抓第一个 0–1 小数（"0.8" / "score: 0.4" 都能命中）。 */
    private static final Pattern SCORE = Pattern.compile("(?<!\\d)(0(?:\\.\\d+)?|1(?:\\.0+)?)");

    private final CascadeProperties props;
    /** temp=0 自评模型；未开自评时为 null。 */
    private final ChatModel rater;

    public ConfidenceGate(CascadeProperties props, ChatModel rater) {
        this.props = props;
        this.rater = rater;
    }

    /** 纯启发式构造（测试 / 未开自评常用）。 */
    public ConfidenceGate(CascadeProperties props) {
        this(props, null);
    }

    /**
     * @return true = 便宜模型答案够用（保留便宜结果）；false = 低置信（升级到强模型）。
     */
    public boolean isConfident(String question, String answer) {
        if (answer == null) return false;
        String trimmed = answer.strip();
        if (trimmed.length() < props.getMinAnswerChars()) {
            log.debug("cascade gate: too short ({} chars) → escalate", trimmed.length());
            return false;
        }
        String lower = trimmed.toLowerCase();
        for (String marker : props.getUncertaintyMarkers()) {
            if (marker != null && !marker.isBlank() && lower.contains(marker.toLowerCase())) {
                log.debug("cascade gate: uncertainty marker '{}' → escalate", marker);
                return false;
            }
        }
        if (props.isSelfRating() && rater != null) {
            double score = selfRate(question, trimmed);
            boolean confident = score >= props.getConfidenceThreshold();
            log.debug("cascade gate: self-rated {} (threshold {}) → {}",
                    score, props.getConfidenceThreshold(), confident ? "keep cheap" : "escalate");
            return confident;
        }
        return true;
    }

    /** temp=0 让便宜模型给自己的答案打 0–1 置信分；解析失败保守判 0.0（升级）。 */
    private double selfRate(String question, String answer) {
        String prompt = """
                你是严格的答案质量评审。判断下面这个「答案」对「问题」的回答质量与置信度。
                只输出一个 0 到 1 之间的小数（保留一位即可），不要输出任何其他文字。
                1.0 = 完全正确且自洽；0.0 = 错误 / 空洞 / 明显不确定。

                问题：%s
                答案：%s
                """.formatted(question == null ? "" : question, answer);
        try {
            String raw = rater.chat(prompt);
            Matcher m = SCORE.matcher(raw == null ? "" : raw);
            if (m.find()) {
                double v = Double.parseDouble(m.group(1));
                return Math.max(0.0, Math.min(1.0, v));
            }
        } catch (Exception e) {
            log.warn("cascade self-rating failed, treating as low-confidence: {}", e.toString());
        }
        return 0.0;
    }
}
