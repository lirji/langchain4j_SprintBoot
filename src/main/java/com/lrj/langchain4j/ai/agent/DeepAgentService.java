package com.lrj.langchain4j.ai.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 深度 Agent 编排：开放式 <strong>plan → act → observe</strong> 循环。每步让 {@link AgentBrain}
 * 结构化决策一个动作，执行后把观察喂回，直到 {@code finish} 或触发终止条件。
 *
 * <p>循环对每步有完全控制权（这正是「深度 Agent」区别于「带工具的 AiService 自动循环」的地方）：
 * <ul>
 *   <li><strong>硬预算</strong> {@code maxSteps} —— 跑满判 {@code MAX_STEPS} 终止，挡 runaway；</li>
 *   <li><strong>循环检测</strong> —— 连续重复同一 (动作,入参) 达 {@code maxRepeats} 判 {@code LOOP} 终止；</li>
 *   <li><strong>工作记忆</strong> scratchpad —— 模型用 note 沉淀结论，跨步重注入（带字符上限截断）；</li>
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
    /** name(lowercase) → action。 */
    private final Map<String, AgentAction> actions = new LinkedHashMap<>();

    public DeepAgentService(AgentBrain brain, List<AgentAction> actions, AgentProperties props) {
        this.brain = brain;
        this.props = props;
        for (AgentAction a : actions) {
            this.actions.put(a.name().toLowerCase(Locale.ROOT), a);
        }
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
        String lastSig = null;
        int repeat = 0;

        for (int n = 1; n <= props.getMaxSteps(); n++) {
            // 取消感知：异步 run 被 Future.cancel(true) 取消时 worker 线程被 interrupt。
            // 每步开头侦测到就提前退出（当前若已在跑某步则跑完——无法中止上游 LLM 生成，与流式同理）。
            if (Thread.currentThread().isInterrupted()) {
                log.info("agent cancelled (interrupted) before step {} (depth={})", n, depth);
                return new Run(goal, steps, bestEffort(scratchpad), "CANCELLED", depth);
            }
            AgentDecision d;
            try {
                d = brain.decide(goal, actionsDesc, scratchpadOrNone(scratchpad), renderHistory(steps));
            } catch (Exception e) {
                // brain 调用/解析失败：不让整个 run 崩，记一步并终止
                log.warn("agent brain failed at step {} (depth={}): {}", n, depth, e.toString());
                steps.add(new Step(n, "", "", "", "(brain error: " + e.getMessage() + ")"));
                return new Run(goal, steps, bestEffort(scratchpad), "ERROR", depth);
            }

            String action = d.action() == null ? "" : d.action().trim();
            appendNote(scratchpad, d.note());

            if (action.equalsIgnoreCase(FINISH)) {
                log.info("agent finished in {} step(s) (depth={})", n - 1, depth);
                return new Run(goal, steps, safe(d.finalAnswer()), "DONE", depth);
            }

            // 循环检测：连续重复同一 (动作,入参)
            String sig = action.toLowerCase(Locale.ROOT) + "|" + safe(d.actionInput());
            repeat = sig.equals(lastSig) ? repeat + 1 : 1;
            lastSig = sig;
            if (repeat >= props.getMaxRepeats()) {
                steps.add(new Step(n, safe(d.thought()), action, safe(d.actionInput()),
                        "(stopped: repeated the same action " + repeat + "x without progress)"));
                log.info("agent stopped on repeat-loop at step {} (depth={})", n, depth);
                return new Run(goal, steps, bestEffort(scratchpad), "LOOP", depth);
            }

            String observation = dispatch(action, safe(d.actionInput()), depth);
            steps.add(new Step(n, safe(d.thought()), action, safe(d.actionInput()), observation));
        }

        log.info("agent hit max-steps ({}) without finishing (depth={})", props.getMaxSteps(), depth);
        return new Run(goal, steps, bestEffort(scratchpad), "MAX_STEPS", depth);
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
            // 截断最旧的部分（保留尾部最新结论）
            scratchpad.delete(0, scratchpad.length() - cap);
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
     * @param stopReason DONE（正常 finish）/ MAX_STEPS（跑满预算）/ LOOP（卡死重复）/ ERROR（brain 异常）/
     *                   CANCELLED（被取消、线程 interrupt）
     */
    public record Run(String goal, List<Step> steps, String finalAnswer, String stopReason, int depth) {}

    /** 单步留痕。 */
    public record Step(int n, String thought, String action, String actionInput, String observation) {}
}
