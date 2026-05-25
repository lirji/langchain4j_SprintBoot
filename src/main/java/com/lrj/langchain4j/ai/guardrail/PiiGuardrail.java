package com.lrj.langchain4j.ai.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Blocks responses that look like they leak personally identifiable information
 * (emails, phone numbers, 18-digit IDs that resemble Chinese national IDs).
 * On detection asks the model to redact and try again — LC4j retries up to
 * {@code maxRetries} (default in {@code @OutputGuardrails}).
 */
public class PiiGuardrail implements OutputGuardrail {

    private static final Logger log = LoggerFactory.getLogger(PiiGuardrail.class);

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PHONE_CN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CN = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        String text = responseFromLLM.text();
        if (text == null) return OutputGuardrailResult.success();

        String hit = firstHit(text);
        if (hit == null) return OutputGuardrailResult.success();

        log.warn("PII guardrail blocked output (matched: {})", hit);
        // reprompt(...) is a default method on OutputGuardrail itself, not a static on the result.
        return reprompt(
                "Output contained PII (" + hit + ").",
                "Your previous answer contained personally identifiable information ("
                        + hit + "). Rewrite the answer with the PII redacted as [REDACTED]."
        );
    }

    private static String firstHit(String text) {
        if (EMAIL.matcher(text).find()) return "email";
        if (PHONE_CN.matcher(text).find()) return "phone";
        if (ID_CN.matcher(text).find()) return "id-card";
        return null;
    }
}
