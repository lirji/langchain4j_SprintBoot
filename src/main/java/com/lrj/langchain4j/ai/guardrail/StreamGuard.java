package com.lrj.langchain4j.ai.guardrail;

import java.util.regex.Pattern;

/**
 * 流式回答收口时的事后处理。流式路径（{@code /chat/stream} / A2A {@code message/stream}）的 token 是
 * 逐个发出的，<strong>无法像非流式 guardrail 那样重写</strong> —— 已经发出去的收不回。所以这里只做
 * <strong>append-only 的告警</strong>：缓冲完整答案后扫一遍，命中就在末尾追加一句提示（不改前文）。
 *
 * <p>纯静态、无状态、无 IO —— 可 JUnit 直接测；配置开关（如 A2A 的 input-required 检测）由调用方按
 * 自己的 properties 决定是否调用，本类只提供判定。
 */
public final class StreamGuard {

    private StreamGuard() {}

    /** 澄清式提问的话术线索（中英）。 */
    private static final Pattern CLARIFY_CUE = Pattern.compile(
            "请问|请提供|请明确|请给出|请补充|你是指|您是指|具体是指|哪一[个种]|需要(更多|补充)(信息|细节)?|"
            + "能否(提供|说明|明确)|"
            + "could you (please )?(clarify|provide|specify|tell)|can you (clarify|specify|provide)|"
            + "which .{0,40}?(do you mean|are you referring)|please (provide|specify|clarify)|"
            + "what (do you mean|exactly)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 流式完整答案里若含 PII，返回一句追加告警（无法回收已发 token，只能提示）；无命中返回 null。
     */
    public static String piiWarningOrNull(String fullAnswer) {
        String hit = PiiDetector.firstHit(fullAnswer);
        if (hit == null) return null;
        return "⚠️ 安全提示：本回答可能包含个人敏感信息（" + hit + "），已记录审计，请勿外传。";
    }

    /**
     * 启发式判定回复是否是「澄清式提问」（A2A 用来把终态置成 {@code input-required}）。保守：
     * 必须以问号结尾 + 较短（避免长答案末尾的反问被误判）+ 命中明确澄清话术。
     */
    public static boolean looksLikeClarifyingQuestion(String fullAnswer) {
        if (fullAnswer == null) return false;
        String a = fullAnswer.strip();
        if (a.isEmpty() || a.length() > 240) return false;
        char last = a.charAt(a.length() - 1);
        if (last != '?' && last != '？') return false;
        return CLARIFY_CUE.matcher(a).find();
    }
}
