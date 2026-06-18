package com.lrj.langchain4j.ai.vision;

import com.lrj.langchain4j.ai.guardrail.PiiDetector;
import com.lrj.langchain4j.ai.guardrail.PromptInjectionDetector;
import com.lrj.langchain4j.audit.AuditEventType;
import com.lrj.langchain4j.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 图像入库内容的安全闸门。视觉模型 caption/OCR 出来的文本是<strong>不可信外部输入</strong>——
 * 图里可能藏注入指令（「忽略以上所有指令…」），转写也可能带 PII（证件号/手机号/邮箱）。这段文本
 * 会落进知识库、日后作为 RAG 上下文回灌进 prompt，所以入库前必须过一道闸：
 *
 * <ul>
 *   <li><strong>注入 → 阻断</strong>：复用 {@link PromptInjectionDetector}（规则 + 可选 LLM 分类器，
 *       受 {@code app.guardrail.injection.*} 控制）。命中即拒绝入库（抛 {@code IllegalArgumentException}
 *       → controller 翻 400）+ 审计。阻断而非清洗，因为「被投毒的图」本身就不该进库。</li>
 *   <li><strong>PII → 脱敏</strong>：{@link PiiDetector#redact} 把 email/手机/身份证替换成
 *       {@code [REDACTED-类别]} 再入库 + 审计。脱敏而非阻断，保留文档可用性。</li>
 * </ul>
 *
 * <p>填的是「文本走 Assistant 有 guardrail、图像旁路完全裸奔」的缺口——视觉是 {@code @AiService}
 * 之外独立构造的 ChatModel，LangChain4j 的 {@code @InputGuardrails}/{@code @OutputGuardrails} 管不到它。
 */
@Component
public class VisionContentGuard {

    private static final Logger log = LoggerFactory.getLogger(VisionContentGuard.class);

    private final PromptInjectionDetector injectionDetector;
    private final AuditLogger audit;

    public VisionContentGuard(PromptInjectionDetector injectionDetector, AuditLogger audit) {
        this.injectionDetector = injectionDetector;
        this.audit = audit;
    }

    /**
     * 对 caption/OCR 文本做入库前安全处理。
     *
     * @param text       视觉模型产出的文本
     * @param sourceName 来源文件名（审计 / 报错用）
     * @return 脱敏后的文本（无 PII 时原样返回）
     * @throws IllegalArgumentException 检出注入指令，拒绝入库
     */
    public String sanitizeForIngest(String text, String sourceName) {
        if (text == null || text.isBlank()) {
            return text;
        }

        PromptInjectionDetector.Detection d = injectionDetector.detect(text);
        if (d.injection()) {
            audit.record(AuditEventType.GUARDRAIL_INJECTION_DETECTED, Map.of(
                    "source", "vision-ingest",
                    "file", sourceName == null ? "" : sourceName,
                    "reason", d.reason(),
                    "confidence", d.confidence()));
            log.warn("blocked image ingest '{}': injected instructions in OCR/caption (reason={})",
                    sourceName, d.reason());
            throw new IllegalArgumentException(
                    "image content blocked: detected injected instructions in the image ('" + sourceName + "')");
        }

        String hit = PiiDetector.firstHit(text);
        if (hit != null) {
            audit.record(AuditEventType.GUARDRAIL_PII_REDACTED, Map.of(
                    "source", "vision-ingest",
                    "file", sourceName == null ? "" : sourceName,
                    "category", hit));
            log.info("redacted PII ({}) from image-derived text of '{}' before ingest", hit, sourceName);
            return PiiDetector.redact(text);
        }
        return text;
    }
}
