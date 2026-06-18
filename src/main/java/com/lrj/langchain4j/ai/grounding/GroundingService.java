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
 * <p>v1 只实现 <b>warn 模式</b>：命中任一层就在答案末尾追加一句可信度提示并打 WARN 日志，
 * 不改写、不拒答。refuse / regenerate 留待后续。
 */
@Service
public class GroundingService {

    private static final Logger log = LoggerFactory.getLogger(GroundingService.class);
    private static final Pattern CITATION = Pattern.compile("\\[doc=([^\\]]+)\\]");

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
        if (!props.isEnabled()) {
            return answerCall.get();
        }
        RetrievedSourcesContext.clear();
        try {
            String answer = answerCall.get();
            return verifyAndWarn(answer);
        } finally {
            RetrievedSourcesContext.clear();
        }
    }

    private String verifyAndWarn(String answer) {
        String suffix = warningSuffixOrNull(answer, RetrievedSourcesContext.get());
        return suffix == null ? answer : answer + suffix;
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
