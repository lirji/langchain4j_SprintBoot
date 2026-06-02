package com.lrj.langchain4j.channel.feishu;

import java.util.List;
import java.util.Locale;

/**
 * 入站消息意图分类（M1.B 意图路由）：命中退款/投诉类意图 → 走 refund 工作流（可能挂起人工审批），
 * 其余 → 走 Assistant 对话。纯函数便于单测。
 *
 * <p>v1 用关键词启发式：中文客服里"退款/退货/投诉"等词对意图的指示性很强，零额外 LLM 调用、零延迟。
 * 要更细可换成 {@code ai/routing} 的 LLM 分类器（{@code QueryClassifier}）或加 few-shot——
 * 但先用关键词打地基，跟项目"简单起步、被 eval 证明不够再加"的取向一致。
 */
public final class FeishuIntent {

    private FeishuIntent() {}

    public enum Route { WORKFLOW, CHAT }

    /** 命中即判工作流的关键词（小写匹配，覆盖中英）。 */
    private static final List<String> WORKFLOW_KEYWORDS = List.of(
            "退款", "退货", "退钱", "退单", "退订", "退回", "返款",
            "投诉", "赔偿", "维权", "索赔", "12315",
            "refund", "return", "chargeback", "complaint");

    public static Route classify(String message) {
        if (message == null || message.isBlank()) {
            return Route.CHAT;
        }
        String m = message.toLowerCase(Locale.ROOT);
        for (String kw : WORKFLOW_KEYWORDS) {
            if (m.contains(kw)) {
                return Route.WORKFLOW;
            }
        }
        return Route.CHAT;
    }
}
