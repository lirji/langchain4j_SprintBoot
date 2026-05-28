package com.lrj.langchain4j.ai.grounding;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * faithfulness 校验输出。RAGAS 风格：把答案拆成原子断言，逐条对照 {@code <source>} 判是否被支撑，
 * {@code groundedScore} = 被支撑断言数 / 总断言数。
 *
 * <p>{@code @Description} 会序列化进 JSON Schema 约束模型输出 —— 比在 prompt 里写"请输出 JSON"稳。
 */
public record GroundednessReport(
        @Description("被检索资料支撑的断言占比 0.0-1.0。1.0=每条事实断言都能在 source 里找到依据；"
                + "0.0=全是 source 没有的内容。答案若不含可核实的事实断言（如拒答、闲聊）记 1.0")
        double groundedScore,

        @Description("逐条列出答案中未被任一 <source> 支撑的具体事实断言；全部被支撑或无事实断言则空数组")
        List<String> unsupportedClaims
) {}
