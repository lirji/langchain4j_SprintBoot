package com.lrj.langchain4j.eval;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 评测基线 —— CI 回归门禁的「合格线」。提交进仓库（{@code resources/eval/baseline.json}），
 * 每次改 prompt / 模型 / RAG 配置后跑 {@link BaselineGate} 对照：低于线就判回归、CI 失败。
 *
 * <p>两层门槛：
 * <ul>
 *   <li>全局：{@code minOverallPassRate} / {@code minAverageScore} —— 整体不许跌破</li>
 *   <li>per-case：{@code cases[id]} 的 {@link CaseFloor} —— 单个 case 不许跌破（防「整体没动但关键 case 坏了」）</li>
 * </ul>
 *
 * <p>基线由 {@link BaselineGate#deriveBaseline} 从一次实测 run 减去 {@code slack} 生成
 * （留出 Assistant temp=0.7 的正常抖动空间），不是手填。见 {@code /eval/baseline} 端点。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Baseline(
        double minOverallPassRate,
        double minAverageScore,
        Map<String, CaseFloor> cases
) {
    /** 单个 case 的合格线。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaseFloor(double minPassRate, double minAvgScore) {}

    public Map<String, CaseFloor> safeCases() {
        return cases == null ? Map.of() : cases;
    }
}
