package com.lrj.langchain4j.ai.guardrail;

import com.lrj.langchain4j.audit.AuditEventType;
import com.lrj.langchain4j.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 注入检测两层流水线 —— 规则在前（免费、即时），LLM 分类器在后（按需启用，慢但精准）。
 *
 * <p>{@link PromptInjectionClassifier} 用 {@link ObjectProvider} 注入：未启用时
 * {@code GuardrailConfig} 不创建 Bean，{@code getIfAvailable()} 返回 null，detector 直接跳过
 * LLM 阶段。
 */
@Service
public class PromptInjectionDetector {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionDetector.class);

    private final PromptInjectionProperties props;
    private final ObjectProvider<PromptInjectionClassifier> classifierProvider;
    private final AuditLogger audit;

    public PromptInjectionDetector(PromptInjectionProperties props,
                                   ObjectProvider<PromptInjectionClassifier> classifierProvider,
                                   AuditLogger audit) {
        this.props = props;
        this.classifierProvider = classifierProvider;
        this.audit = audit;
    }

    public Detection detect(String input) {
        if (!props.isEnabled() || input == null || input.isBlank()) {
            return Detection.clean();
        }

        // 第一层：规则匹配（first-hit short-circuit）
        String ruleHit = PromptInjectionRules.firstMatch(input);
        if (ruleHit != null) {
            Detection d = new Detection(true, 1.0, "rule:" + ruleHit);
            emitAudit(d, input);
            return d;
        }

        // 第二层：LLM 分类器（仅在 llm.enabled=true 且 classifier bean 存在时跑）
        if (props.getLlm().isEnabled()) {
            PromptInjectionClassifier classifier = classifierProvider.getIfAvailable();
            if (classifier != null) {
                try {
                    PromptInjectionClassifier.Verdict v = classifier.classify(input);
                    double threshold = props.getLlm().getConfidenceThreshold();
                    if (v.isInjection() && v.confidence() >= threshold) {
                        Detection d = new Detection(true, v.confidence(), "llm:" + v.reason());
                        emitAudit(d, input);
                        return d;
                    }
                } catch (Exception ex) {
                    // LLM 分类失败不让用户请求挂掉 —— 走 fail-open，记 warn
                    log.warn("prompt-injection LLM classifier failed; falling open: {}", ex.getMessage());
                }
            }
        }

        return Detection.clean();
    }

    private void emitAudit(Detection d, String input) {
        // 截断原文避免 audit 行太大（攻击 payload 可能很长），保留前 200 字符方便复盘
        String snippet = input.length() > 200 ? input.substring(0, 200) + "…" : input;
        audit.record(AuditEventType.GUARDRAIL_INJECTION_DETECTED, Map.of(
                "reason", d.reason(),
                "confidence", d.confidence(),
                "action", props.getAction().name(),
                "inputSnippet", snippet));
    }

    public PromptInjectionProperties.Action action() {
        return props.getAction();
    }

    public record Detection(boolean injection, double confidence, String reason) {
        static Detection clean() { return new Detection(false, 0.0, ""); }
    }
}
