package com.lrj.langchain4j.ai.chaining;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt Chaining 编排：把输入依次喂过一串 {@link ChainStep}，每步一次 LLM 调用、只处理上一步输出，
 * 步间执行确定性 gate；gate 不过就<strong>短路</strong>终止（Anthropic Prompt Chaining 模式）。
 *
 * <p>这是「预定义代码路径编排 LLM 调用」——步骤顺序与 gate 写死在配置/代码里，不由模型决定流程，
 * 因此可重复、可单测、可控。与固定 DAG 的 {@code multiagent}（Orchestrator-Workers）正交：链是<strong>顺序</strong>
 * 依赖、DAG 是<strong>并行</strong>分层。
 */
public class PromptChainService {

    private static final Logger log = LoggerFactory.getLogger(PromptChainService.class);

    private final ChainLink link;

    public PromptChainService(ChainLink link) {
        this.link = link;
    }

    /** 单步产物：输出 + gate 结果（未通过时带原因）。 */
    public record StepResult(String name, String output, boolean gatePassed, String gateReason) {}

    /**
     * 整条链的产物。
     *
     * @param completed true = 全部步骤通过、{@code finalOutput} 是最后一步输出；
     *                  false = 某步 gate 未过被短路，{@code finalOutput} 是被拦下的那步输出，最后一个
     *                  {@code StepResult.gatePassed=false} 指明卡点。
     */
    public record ChainRun(String input, List<StepResult> steps, String finalOutput, boolean completed) {}

    public ChainRun run(String input, List<ChainStep> steps) {
        String current = safe(input);
        List<StepResult> results = new ArrayList<>();
        for (ChainStep step : steps) {
            String output = safe(link.transform(step.getInstruction(), current));
            String gateReason = gateFailure(step, output);
            boolean passed = gateReason == null;
            results.add(new StepResult(step.getName(), output, passed, gateReason));
            if (!passed) {
                log.info("prompt chain stopped at step '{}' gate: {}", step.getName(), gateReason);
                return new ChainRun(input, results, output, false);
            }
            current = output;
        }
        return new ChainRun(input, results, current, true);
    }

    /** 确定性 gate 判定：返回失败原因；null = 通过。 */
    private String gateFailure(ChainStep s, String out) {
        if (s.getGateMinLength() > 0 && out.length() < s.getGateMinLength()) {
            return "输出过短（" + out.length() + " < " + s.getGateMinLength() + " 字符）";
        }
        if (notBlank(s.getGateMustContain()) && !out.contains(s.getGateMustContain())) {
            return "缺少必需内容：" + s.getGateMustContain();
        }
        if (notBlank(s.getGateMustMatch())) {
            try {
                if (!Pattern.compile(s.getGateMustMatch()).matcher(out).find()) {
                    return "未命中模式：" + s.getGateMustMatch();
                }
            } catch (RuntimeException e) {
                // 坏正则不该炸整条链，记警告当作未配置该 gate
                log.warn("invalid gate regex '{}' on step '{}', skipping this gate", s.getGateMustMatch(), s.getName());
            }
        }
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
