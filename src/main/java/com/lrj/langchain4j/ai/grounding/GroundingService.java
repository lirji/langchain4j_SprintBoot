package com.lrj.langchain4j.ai.grounding;

import com.lrj.langchain4j.config.GroundingProperties;
import com.lrj.langchain4j.rag.RetrievedSourcesContext;
import com.lrj.langchain4j.rag.TaggedSourceContentInjector;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 事实幻觉的事后校验编排。两层叠加，仅在 {@code app.rag.grounding.enabled=true} 且本轮
 * <strong>确实检索到 source</strong> 时才跑（没检索就跳过，不浪费 token）：
 *
 * <ul>
 *   <li><b>Layer 0（确定性，零 LLM）</b>：答案里 {@code [doc=ID]} 引用的 id 必须在检索集合里，
 *       否则就是编造引用。</li>
 *   <li><b>Layer 1（faithfulness）</b>：把 source 原文 + 答案喂 {@link GroundednessChecker}，
 *       聚合分低于 {@code threshold} 视为可能含未被资料支撑的内容。</li>
 * </ul>
 *
 * <p>命中闸门后的处置由 {@code app.rag.grounding.on-fail} 决定（{@link GroundingProperties.OnFail}）：
 * <ul>
 *   <li>{@code WARN}（默认）— 末尾追加可信度提示，不改写不拒答（历史行为）；</li>
 *   <li>{@code REFUSE} — 用安全弃答话术替换整个答案（宁可不答）；</li>
 *   <li>{@code REGENERATE} — 带纠正指令重生成最多 {@code max-regenerations} 次，仍不过阈降级为 WARN。</li>
 * </ul>
 * REFUSE/REGENERATE 让「验证」真正接回「生成」形成闭环（Loop Engineering 视角）。REGENERATE 需调用方走
 * {@link #applyToFreshAnswer(Function)} 重载（把纠正指令拼进 prompt）；{@link #applyToFreshAnswer(Supplier)}
 * 老签名仍可用，其 REGENERATE 退化为「原样重跑」（无纠正信号）。流式路径无法重写/重生成，仅 WARN。
 */
@Service
public class GroundingService {

    private static final Logger log = LoggerFactory.getLogger(GroundingService.class);
    private static final Pattern CITATION = Pattern.compile("\\[doc=([^\\]]+)\\]");

    /** REGENERATE 模式拼进 prompt 的纠正指令（作为 {@link #applyToFreshAnswer(Function)} 的入参传给调用方）。 */
    private static final String REGEN_HINT =
            "\n\n[系统提示] 你上一版回答可能包含未被检索资料充分支撑的内容。请**仅依据**提供的 <source> 资料重新作答；"
            + "若资料不足以支撑，请明确说「未在文档中找到相关内容」，不要编造或外推。";

    /** REFUSE 模式的替换话术。刻意避开 ABSTENTION_MARKERS 里的措辞以免与「诚实弃答」混淆统计。 */
    private static final String REFUSAL =
            "抱歉，我无法从检索到的资料中充分核实这个回答，为避免提供不准确的信息，这里暂不作答。"
            + "建议您查阅原始资料，或换一种问法。";

    /**
     * 弃答话术标记。跟 {@code AssistantProperties.citationPolicy} 契约的"未在文档中找到相关内容"闭环，
     * 外加几个常见自然变体。命中即视为无事实断言、跳过 grounding 校验。
     */
    private static final List<String> ABSTENTION_MARKERS = List.of(
            "未在文档中找到", "资料里没有提到", "资料中没有提到", "资料里未提到",
            "未找到相关内容", "没有找到相关内容", "文档中未提到", "文档中没有提到");

    private final GroundingProperties props;
    private final ObjectProvider<GroundednessChecker> checkerProvider;

    public GroundingService(GroundingProperties props,
                            ObjectProvider<GroundednessChecker> checkerProvider) {
        this.props = props;
        this.checkerProvider = checkerProvider;
    }

    /**
     * 包裹一次会产生检索的回答调用：执行 {@code answerCall}（其间 {@link com.lrj.langchain4j.rag.TaggedSourceContentInjector}
     * 会把 source 写进 {@link RetrievedSourcesContext}），随后做 grounding 后校验。
     *
     * <p>关闭时是零开销直通；try/finally 清理 ThreadLocal 防线程复用串数据。
     */
    public String applyToFreshAnswer(Supplier<String> answerCall) {
        // 老签名桥接到 Function 形式：忽略纠正指令 → REGENERATE 退化为原样重跑（无纠正信号）。
        return applyToFreshAnswer(hint -> answerCall.get());
    }

    /**
     * 可重生成的重载：{@code answerCall} 接收一个<strong>纠正指令</strong>（首次为空串，REGENERATE 重试时非空），
     * 调用方应把它拼进用户 prompt 再调模型。据此实现「验证 → 生成」闭环。
     *
     * <p>关闭时零开销直通；每次尝试 try/finally 清 {@link RetrievedSourcesContext} 防线程复用串数据。
     */
    public String applyToFreshAnswer(Function<String, String> answerCall) {
        if (!props.isEnabled()) {
            return answerCall.apply("");
        }
        GroundingProperties.OnFail mode = props.getOnFail();
        int maxRegen = mode == GroundingProperties.OnFail.REGENERATE
                ? Math.max(0, props.getMaxRegenerations()) : 0;

        String answer = "";
        String suffix = null;
        String hint = "";
        for (int attempt = 0; attempt <= maxRegen; attempt++) {
            RetrievedSourcesContext.clear();
            try {
                answer = answerCall.apply(hint);
                suffix = warningSuffixOrNull(answer, RetrievedSourcesContext.get());
            } finally {
                RetrievedSourcesContext.clear();
            }
            if (suffix == null) {
                return answer; // 已被支撑 / 无检索 / 诚实弃答 → 直接返回
            }
            if (attempt < maxRegen) {
                log.info("grounding gate hit, regenerating (attempt {}/{})", attempt + 1, maxRegen);
                hint = REGEN_HINT;
            }
        }
        return onFailResult(mode, answer, suffix);
    }

    /** 命中闸门且（REGENERATE 时）重试耗尽后的最终处置。 */
    private String onFailResult(GroundingProperties.OnFail mode, String answer, String suffix) {
        switch (mode) {
            case REFUSE:
                log.warn("grounding gate → REFUSE：以安全话术替换未被充分支撑的答案");
                return REFUSAL;
            case REGENERATE:
                log.warn("grounding gate → REGENERATE 重试耗尽，降级为 WARN（保留最佳尝试 + 提示）");
                return answer + suffix;
            case WARN:
            default:
                return answer + suffix;
        }
    }

    /**
     * 流式路径的 grounding 后校验入口。流式回调线程拿不到 {@link RetrievedSourcesContext}（ThreadLocal 在
     * 别的线程），改由调用方用 {@code TokenStream.onRetrieved} 捕获的 {@link Content} 列表传入。
     * 返回要<strong>追加</strong>的可信度提示后缀（token 已发出无法重写），无告警返回 null。
     */
    public String streamWarningOrNull(List<Content> retrieved, String answer) {
        if (!props.isEnabled() || retrieved == null || retrieved.isEmpty()) {
            return null;
        }
        List<RetrievedSourcesContext.Source> sources = new ArrayList<>(retrieved.size());
        for (int i = 0; i < retrieved.size(); i++) {
            var seg = retrieved.get(i).textSegment();
            sources.add(new RetrievedSourcesContext.Source(TaggedSourceContentInjector.inferId(seg, i), seg.text()));
        }
        return warningSuffixOrNull(answer, sources);
    }

    /** 算出要追加的可信度提示后缀（含前导换行），无告警返回 null。同步/流式两条路径共用。 */
    private String warningSuffixOrNull(String answer, List<RetrievedSourcesContext.Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return null; // 本轮没走 RAG，无可校验
        }

        // 诚实弃答（"未在文档中找到…"）没有事实断言 —— 无可幻觉，不该触发可信度提示。
        // 即便检索返回了不相关片段，弃答也是正确行为。靠 citationPolicy 契约里的固定话术识别，
        // 比依赖 Layer 1 checker 在弱模型上稳定判 1.0 可靠（实测 qwen3:8b 会把弃答误判成 0.0）。
        if (isAbstention(answer)) {
            return null;
        }

        List<String> warnings = new ArrayList<>();

        // Layer 0 — 引用完整性
        List<String> fabricated = fabricatedCitations(answer, sources);
        if (!fabricated.isEmpty()) {
            warnings.add("引用了未检索到的来源：" + String.join("、", fabricated));
        }

        // Layer 1 — faithfulness
        GroundednessChecker checker = checkerProvider.getIfAvailable();
        if (checker != null) {
            try {
                GroundednessReport report = checker.check(renderSources(sources), answer);
                if (report.groundedScore() < props.getThreshold()) {
                    String detail = (report.unsupportedClaims() == null || report.unsupportedClaims().isEmpty())
                            ? ""
                            : "：" + String.join("；", report.unsupportedClaims());
                    warnings.add(String.format("grounding=%.2f < %.2f，可能含未被资料支撑的内容%s",
                            report.groundedScore(), props.getThreshold(), detail));
                }
            } catch (Exception e) {
                log.warn("grounding faithfulness check failed, skipping Layer 1", e);
            }
        }

        if (warnings.isEmpty()) {
            return null;
        }
        log.warn("RAG grounding warning ({} sources): {}", sources.size(), warnings);
        return "\n\n⚠️ 可信度提示：" + String.join("；", warnings) + "。请以原始资料为准。";
    }

    /** 是否是诚实弃答（无事实断言）。命中 {@link #ABSTENTION_MARKERS} 任一即算。 */
    private boolean isAbstention(String answer) {
        if (answer == null) return false;
        String a = answer.strip();
        return ABSTENTION_MARKERS.stream().anyMatch(a::contains);
    }

    /** 提取答案里所有 {@code [doc=ID]} 引用，返回不在检索集合里的（去重保序）。 */
    private List<String> fabricatedCitations(String answer, List<RetrievedSourcesContext.Source> sources) {
        Set<String> valid = sources.stream()
                .map(RetrievedSourcesContext.Source::id)
                .collect(Collectors.toSet());
        Set<String> bad = new LinkedHashSet<>();
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            String id = m.group(1).trim();
            if (!valid.contains(id)) {
                bad.add(id);
            }
        }
        return new ArrayList<>(bad);
    }

    private String renderSources(List<RetrievedSourcesContext.Source> sources) {
        StringBuilder sb = new StringBuilder();
        for (RetrievedSourcesContext.Source s : sources) {
            sb.append("<source id=\"").append(s.id()).append("\">\n")
              .append(s.text()).append("\n</source>\n");
        }
        return sb.toString();
    }
}
