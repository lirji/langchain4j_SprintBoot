package com.lrj.langchain4j.ai.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

/**
 * 深度 Agent 编排：开放式 <strong>plan → act → observe</strong> 循环。每步让 {@link AgentBrain}
 * 结构化决策一个动作，执行后把观察喂回，直到 {@code finish} 或触发终止条件。
 *
 * <p>循环对每步有完全控制权（这正是「深度 Agent」区别于「带工具的 AiService 自动循环」的地方）：
 * <ul>
 *   <li><strong>三维预算</strong> —— 步数 {@code maxSteps}（{@code MAX_STEPS}）/ 墙钟 {@code maxWallClockMs}
 *       （{@code TIMEOUT}）/ 近似 token {@code maxTokens}（{@code BUDGET}），任一超限即停，挡 runaway；</li>
 *   <li><strong>循环检测</strong> —— 滑窗内同一 (动作,入参) 出现达 {@code maxRepeats} 判 {@code LOOP}（含 A→B→A→B 震荡）；</li>
 *   <li><strong>工作记忆</strong> scratchpad —— 模型用 note 沉淀结论，跨步重注入；溢出按 bullet 行压缩
 *       （可选 {@link ScratchpadSummarizer} LLM 摘要，否则丢弃最旧整条）；</li>
 *   <li><strong>子 Agent 派生</strong> delegate —— 深度受 {@code maxDepth} 限，挡无限自我派生；</li>
 *   <li><strong>逐步 trace</strong> —— 每步的 thought/action/observation 全留痕，便于调试与 eval。</li>
 * </ul>
 *
 * <p>token 成本：每步 1 次 brain LLM 调用（走主 {@code ChatModel}，已挂 metrics + per-tenant
 * token 预算 listener），外加动作自身可能的开销。worst case ≈ maxSteps 次 LLM 调用。
 */
public class DeepAgentService {

    private static final Logger log = LoggerFactory.getLogger(DeepAgentService.class);

    /** 内置动作名（不走 {@link AgentAction} 注册表，循环直接识别）。 */
    static final String FINISH = "finish";
    static final String DELEGATE = "delegate";

    private final AgentBrain brain;
    private final AgentProperties props;
    /** scratchpad 溢出时的 LLM 摘要器；null = 未装配（退化为按行丢弃最旧）。 */
    private final ScratchpadSummarizer summarizer;
    /** 墙钟来源（可注入，便于单测确定性推进时间）。 */
    private final LongSupplier clock;
    /** 近似 token 估算（可注入，默认字符/4 启发式）。 */
    private final ToIntFunction<String> tokenEstimator;
    /** name(lowercase) → action。 */
    private final Map<String, AgentAction> actions = new LinkedHashMap<>();

    public DeepAgentService(AgentBrain brain, List<AgentAction> actions, AgentProperties props) {
        this(brain, actions, props, null);
    }

    public DeepAgentService(AgentBrain brain, List<AgentAction> actions, AgentProperties props,
                            ScratchpadSummarizer summarizer) {
        this(brain, actions, props, summarizer, System::currentTimeMillis, DeepAgentService::approxTokens);
    }

    /** 测试用：注入 clock / estimator，让墙钟与 token 预算可确定性断言。 */
    DeepAgentService(AgentBrain brain, List<AgentAction> actions, AgentProperties props,
                     ScratchpadSummarizer summarizer, LongSupplier clock, ToIntFunction<String> tokenEstimator) {
        this.brain = brain;
        this.props = props;
        this.summarizer = summarizer;
        this.clock = clock;
        this.tokenEstimator = tokenEstimator;
        for (AgentAction a : actions) {
            this.actions.put(a.name().toLowerCase(Locale.ROOT), a);
        }
    }

    /** 近似 token 数：字符/4 启发式（非精确计费，只作循环内安全上限）。 */
    static int approxTokens(String s) {
        return s == null || s.isEmpty() ? 0 : (s.length() + 3) / 4;
    }

    public Run run(String goal) {
        try {
            return run(goal, 0);
        } finally {
            // 顶层 run 收尾：回调实现了 AgentRunListener 的动作释放跨步资源（如 Browser-use 关页面）
            for (AgentAction a : actions.values()) {
                if (a instanceof AgentRunListener l) {
                    try {
                        l.onRunEnd();
                    } catch (Exception e) {
                        log.warn("run-end cleanup failed for action {}: {}", a.name(), e.toString());
                    }
                }
            }
            // 清掉中断标志（若本次 run 是被取消的），避免污染线程池里复用本线程的下一个任务。
            // 在最外层 finally 统一清：run 期间标志保持置位，使所有嵌套 depth 都能侦测到取消并退出。
            Thread.interrupted();
        }
    }

    Run run(String goal, int depth) {
        List<Step> steps = new ArrayList<>();
        StringBuilder scratchpad = new StringBuilder();
        String actionsDesc = describeActions(depth);
        // 循环检测滑窗：记最近 window 步的 (动作|入参) 签名；窗口内某签名出现达 maxRepeats 次 → LOOP。
        // 实际窗口取 max(loopWindow, maxRepeats)，防配置把窗口设得比阈值还小导致永不触发。
        int loopWindow = Math.max(props.getLoopWindow(), props.getMaxRepeats());
        Deque<String> recentSigs = new ArrayDeque<>();
        long deadline = props.getMaxWallClockMs() > 0 ? clock.getAsLong() + props.getMaxWallClockMs() : 0;
        int tokensUsed = 0;

        for (int n = 1; n <= props.getMaxSteps(); n++) {
            // 取消感知：异步 run 被 Future.cancel(true) 取消时 worker 线程被 interrupt。
            // 每步开头侦测到就提前退出（当前若已在跑某步则跑完——无法中止上游 LLM 生成，与流式同理）。
            if (Thread.currentThread().isInterrupted()) {
                log.info("agent cancelled (interrupted) before step {} (depth={})", n, depth);
                return new Run(goal, steps, bestEffort(scratchpad), "CANCELLED", depth);
            }
            // 墙钟预算：单步 LLM/动作偶发慢时，纯步数上限挡不住耗时跑飞。软界——正在跑的步跑完，下一步前才停。
            if (deadline > 0 && clock.getAsLong() >= deadline) {
                log.info("agent hit wall-clock budget ({}ms) before step {} (depth={})",
                        props.getMaxWallClockMs(), n, depth);
                return new Run(goal, steps, bestEffort(scratchpad), "TIMEOUT", depth);
            }
            // token 预算：上下文每步重注入、越滚越大；累计估算超阈就停，别烧到全局配额上限才停。
            if (props.getMaxTokens() > 0 && tokensUsed >= props.getMaxTokens()) {
                log.info("agent hit token budget (~{}/{}) before step {} (depth={})",
                        tokensUsed, props.getMaxTokens(), n, depth);
                return new Run(goal, steps, bestEffort(scratchpad), "BUDGET", depth);
            }

            String scratch = scratchpadOrNone(scratchpad);
            String history = renderHistory(steps);
            // brain 单步决策带重试：结构化输出偶发解析失败 / provider 抖动不该直接终结整个 run。
            AgentDecision d = null;
            Exception lastError = null;
            int attempts = 1 + Math.max(0, props.getBrainMaxRetries());
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    d = brain.decide(goal, actionsDesc, scratch, history);
                    lastError = null;
                    break;
                } catch (Exception e) {
                    lastError = e;
                    log.warn("agent brain failed at step {} attempt {}/{} (depth={}): {}",
                            n, attempt, attempts, depth, e.toString());
                    if (attempt < attempts && props.getBrainRetryBackoffMs() > 0) {
                        try {
                            Thread.sleep(props.getBrainRetryBackoffMs());
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return new Run(goal, steps, bestEffort(scratchpad), "CANCELLED", depth);
                        }
                    }
                }
            }
            if (d == null) {
                // 重试耗尽仍失败：不让整个 run 崩，记一步并终止
                steps.add(new Step(n, "", "", "",
                        "(brain error after " + attempts + " attempt(s): "
                                + (lastError == null ? "" : lastError.getMessage()) + ")"));
                return new Run(goal, steps, bestEffort(scratchpad), "ERROR", depth);
            }
            tokensUsed += tokenEstimator.applyAsInt(goal) + tokenEstimator.applyAsInt(actionsDesc)
                    + tokenEstimator.applyAsInt(scratch) + tokenEstimator.applyAsInt(history)
                    + tokenEstimator.applyAsInt(decisionText(d));

            String action = d.action() == null ? "" : d.action().trim();
            appendNote(scratchpad, d.note());

            if (action.equalsIgnoreCase(FINISH)) {
                log.info("agent finished in {} step(s) (depth={})", n - 1, depth);
                return new Run(goal, steps, safe(d.finalAnswer()), "DONE", depth);
            }

            // 循环检测：窗口内同一 (动作,入参) 出现达 maxRepeats 次（含震荡 A→B→A→B）
            String sig = action.toLowerCase(Locale.ROOT) + "|" + safe(d.actionInput());
            recentSigs.addLast(sig);
            while (recentSigs.size() > loopWindow) recentSigs.removeFirst();
            long occ = recentSigs.stream().filter(sig::equals).count();
            if (occ >= props.getMaxRepeats()) {
                steps.add(new Step(n, safe(d.thought()), action, safe(d.actionInput()),
                        "(stopped: action repeated " + occ + "x within last " + recentSigs.size()
                                + " steps without progress)"));
                log.info("agent stopped on repeat-loop at step {} (depth={})", n, depth);
                return new Run(goal, steps, bestEffort(scratchpad), "LOOP", depth);
            }

            String observation = dispatch(action, safe(d.actionInput()), depth);
            tokensUsed += tokenEstimator.applyAsInt(observation);
            steps.add(new Step(n, safe(d.thought()), action, safe(d.actionInput()), observation));
        }

        log.info("agent hit max-steps ({}) without finishing (depth={})", props.getMaxSteps(), depth);
        return new Run(goal, steps, bestEffort(scratchpad), "MAX_STEPS", depth);
    }

    /** 决策文本化（用于 token 估算）。 */
    private static String decisionText(AgentDecision d) {
        return safe(d.thought()) + safe(d.action()) + safe(d.actionInput())
                + safe(d.note()) + safe(d.finalAnswer());
    }

    private String dispatch(String action, String input, int depth) {
        if (action.isBlank()) {
            return "no action chosen; pick one from the available actions or finish";
        }
        if (action.equalsIgnoreCase(DELEGATE)) {
            if (!props.isAllowDelegation()) {
                return "delegation is disabled";
            }
            if (depth >= props.getMaxDepth()) {
                return "delegation denied: max depth (" + props.getMaxDepth() + ") reached — solve it directly";
            }
            Run sub = run(input, depth + 1);
            return "[sub-agent " + sub.stopReason() + "] " + safe(sub.finalAnswer());
        }
        AgentAction a = actions.get(action.toLowerCase(Locale.ROOT));
        if (a == null) {
            return "unknown action '" + action + "'. choose from: " + String.join(", ", actionNames(depth));
        }
        try {
            return safe(a.run(input));
        } catch (Exception e) {
            return "action error: " + e.getMessage();
        }
    }

    // -------- prompt rendering --------

    private String describeActions(int depth) {
        StringBuilder sb = new StringBuilder();
        for (AgentAction a : actions.values()) {
            sb.append("- ").append(a.name()).append(": ").append(a.description()).append('\n');
        }
        if (props.isAllowDelegation() && depth < props.getMaxDepth()) {
            sb.append("- ").append(DELEGATE)
                    .append(": 把一个独立的子目标派给子 Agent 处理；actionInput 写子目标\n");
        }
        sb.append("- ").append(FINISH).append(": 任务已完成，在 finalAnswer 给出最终答案\n");
        return sb.toString();
    }

    private List<String> actionNames(int depth) {
        List<String> names = new ArrayList<>(actions.keySet());
        if (props.isAllowDelegation() && depth < props.getMaxDepth()) names.add(DELEGATE);
        names.add(FINISH);
        return names;
    }

    private String renderHistory(List<Step> steps) {
        if (steps.isEmpty()) return "(暂无)";
        int from = Math.max(0, steps.size() - props.getHistoryWindow());
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < steps.size(); i++) {
            Step s = steps.get(i);
            sb.append(s.n()).append(". ").append(s.action());
            if (!s.actionInput().isBlank()) sb.append("(").append(s.actionInput()).append(")");
            sb.append(" -> ").append(s.observation()).append('\n');
        }
        return sb.toString();
    }

    private void appendNote(StringBuilder scratchpad, String note) {
        if (note == null || note.isBlank()) return;
        if (scratchpad.length() > 0) scratchpad.append('\n');
        scratchpad.append("- ").append(note.trim());
        int cap = props.getMaxScratchpadChars();
        if (cap > 0 && scratchpad.length() > cap) {
            compactScratchpad(scratchpad, cap);
        }
    }

    /**
     * scratchpad 溢出压缩：按 bullet 行保留尾部最新的、把被挤出的最旧结论
     * （装配了 {@link ScratchpadSummarizer} 则 LLM 摘成一条、否则整条丢弃）。
     * 相比旧版盲砍字符前缀，不再腰斩半行；末尾再做一次 line-aware 硬保护确保 ≤ cap。
     */
    private void compactScratchpad(StringBuilder scratchpad, int cap) {
        String[] lines = scratchpad.toString().split("\n", -1);
        // 有摘要器时给摘要 bullet 留 1/4 空间，避免压完又超 cap
        int headroom = summarizer != null ? Math.max(1, cap - cap / 4) : cap;

        Deque<String> kept = new ArrayDeque<>();
        int len = 0, i = lines.length - 1;
        for (; i >= 0; i--) {
            int add = lines[i].length() + 1;
            if (len + add > headroom && !kept.isEmpty()) break;
            kept.addFirst(lines[i]);
            len += add;
        }
        // lines[0..i] 是被挤出的更旧部分
        StringBuilder older = new StringBuilder();
        for (int j = 0; j <= i; j++) {
            if (older.length() > 0) older.append('\n');
            older.append(lines[j]);
        }

        StringBuilder result = new StringBuilder();
        if (summarizer != null && older.length() > 0) {
            try {
                String s = summarizer.summarize(older.toString());
                if (s != null && !s.isBlank()) {
                    result.append("- (早期结论摘要) ").append(s.trim());
                }
            } catch (Exception e) {
                log.warn("scratchpad summarize failed, dropping oldest instead: {}", e.toString());
            }
        }
        for (String k : kept) {
            if (result.length() > 0) result.append('\n');
            result.append(k);
        }

        scratchpad.setLength(0);
        scratchpad.append(result);
        // 硬保护：摘要可能偏长导致仍超 cap → 从最旧行整条丢，直到 ≤ cap
        while (scratchpad.length() > cap) {
            int nl = scratchpad.indexOf("\n");
            if (nl < 0) { scratchpad.setLength(cap); break; }
            scratchpad.delete(0, nl + 1);
        }
    }

    private static String scratchpadOrNone(StringBuilder scratchpad) {
        return scratchpad.length() == 0 ? "(空)" : scratchpad.toString();
    }

    /** 没正常 finish 时的兜底答案：把工作记忆交回去，至少不丢已得结论。 */
    private static String bestEffort(StringBuilder scratchpad) {
        return scratchpad.length() == 0 ? "" : scratchpad.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // -------- result model --------

    /**
     * 一次 run 的结果。
     *
     * @param stopReason DONE（正常 finish）/ MAX_STEPS（跑满步数预算）/ TIMEOUT（超墙钟预算）/
     *                   BUDGET（超近似 token 预算）/ LOOP（卡死重复，含震荡）/ ERROR（brain 异常）/
     *                   CANCELLED（被取消、线程 interrupt）
     */
    public record Run(String goal, List<Step> steps, String finalAnswer, String stopReason, int depth) {}

    /** 单步留痕。 */
    public record Step(int n, String thought, String action, String actionInput, String observation) {}
}
