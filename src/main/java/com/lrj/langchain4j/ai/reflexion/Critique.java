package com.lrj.langchain4j.ai.reflexion;

import dev.langchain4j.model.output.structured.Description;

/**
 * 多维评分输出，3 个正交维度 + 1 句最该改进的点。
 *
 * <p>{@code @Description} 会被序列化进发给 LLM 的 JSON Schema —— 描述写得越精确，
 * 模型输出越稳。Schema 约束 + Structured Output 比在 prompt 里写"请用 JSON"管用得多。
 *
 * <p>聚合分由 {@link ReflexiveService} 按 {@code app.reflexion.weights.*} 加权计算，
 * 不要在这里硬编码。
 */
public record Critique(
        @Description("事实正确性 0.0-1.0。0.0=明显事实错误或编造；0.5=大体正确但有小错或不可核实声明；1.0=每一条都可核实")
        double correctness,

        @Description("完整性 0.0-1.0。0.0=忽略问题或只回答了多个子问题之一；0.5=主要点答了但漏次要方面；1.0=问到的都覆盖了")
        double completeness,

        @Description("清晰度 0.0-1.0。0.0=空泛/啰嗦/回避；0.5=能懂但累赘或抽象；1.0=直接、具体、结构清晰、没废话")
        double clarity,

        @Description("最该改进的单个点，一句话；若答案已足够好则写 'n/a'")
        String mainIssue
) {}
