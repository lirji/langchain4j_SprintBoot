package com.lrj.langchain4j.ai.guardrail;

import java.util.regex.Pattern;

/**
 * PII 检测的共享规则（email / 中国手机号 / 18 位身份证）。抽出来给两处复用：
 * <ul>
 *   <li>{@link PiiGuardrail} —— 非流式 output guardrail，命中即 reprompt 重写为 [REDACTED]</li>
 *   <li>流式后处理（{@code StreamGuard}）—— token 已逐个发出无法回收，命中只能在末尾追加告警</li>
 * </ul>
 * 纯静态、无状态，便于 JUnit 直接测。
 */
public final class PiiDetector {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PHONE_CN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CN = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    private PiiDetector() {}

    /** 返回首个命中的 PII 类别（{@code email|phone|id-card}），无命中返回 null。 */
    public static String firstHit(String text) {
        if (text == null) return null;
        if (EMAIL.matcher(text).find()) return "email";
        if (PHONE_CN.matcher(text).find()) return "phone";
        if (ID_CN.matcher(text).find()) return "id-card";
        return null;
    }

    /**
     * 把文本里所有 email / 手机号 / 身份证号替换成 {@code [REDACTED-类别]}。用于<strong>入库前脱敏</strong>
     * （如图片 OCR 转写出的证件号）——既挡 PII 落库，又保留上下文可读。无命中原样返回。
     */
    public static String redact(String text) {
        if (text == null || text.isBlank()) return text;
        String out = ID_CN.matcher(text).replaceAll("[REDACTED-id-card]");
        out = EMAIL.matcher(out).replaceAll("[REDACTED-email]");
        out = PHONE_CN.matcher(out).replaceAll("[REDACTED-phone]");
        return out;
    }
}
