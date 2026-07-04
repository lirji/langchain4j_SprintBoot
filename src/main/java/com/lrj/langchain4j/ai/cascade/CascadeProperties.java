package com.lrj.langchain4j.ai.cascade;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code app.llm.cascade.*} 绑定 —— Model Cascade / Cost Routing 配置。
 *
 * <p>核心思路：先用**便宜模型**（{@code cheap-model}）作答，只有当 {@link ConfidenceGate}
 * 判定该答案「低置信」时，才升级到**强模型**（{@code strong-model}）重答。绝大多数简单问题
 * 便宜模型就搞定，强模型只在需要时才烧钱，整体成本大幅下降。
 *
 * <p>默认关（{@code enabled=false}）：整个 {@link CascadeConfig} 条件化，关闭时零装配、零开销。
 */
public class CascadeProperties {

    private boolean enabled = false;

    /**
     * 便宜模型的模型名（覆盖当前 provider 的默认 model-name）。留空则用当前 provider 配的 model-name。
     * 例：openai 下填 {@code gpt-4o-mini}；anthropic 下填 {@code claude-haiku-4-5}。
     */
    private String cheapModel;

    /**
     * 强模型的模型名（覆盖当前 provider 的默认 model-name）。留空则用当前 provider 配的 model-name
     * （此时 cascade 退化为「便宜=强」，仅演示不省钱）。
     * 例：openai 下填 {@code gpt-4o}；anthropic 下填 {@code claude-sonnet-4-6}。
     */
    private String strongModel;

    /**
     * 自评置信阈值：仅当 {@code self-rating=true} 时生效。便宜模型对自己答案自评分 {@code < threshold}
     * 触发升级。启发式（拒答/不确定/过短标记）不受此值影响，命中即升级。
     */
    private double confidenceThreshold = 0.6;

    /** 便宜模型答案短于此字符数视为「信息不足」→ 升级。 */
    private int minAnswerChars = 8;

    /**
     * 是否在启发式之外再做一次 temp=0 自评（多一次便宜模型调用）。默认关 —— 启发式已能拦住
     * 绝大多数明显低质答案，自评是精度换成本的可选增强。
     */
    private boolean selfRating = false;

    /**
     * 不确定 / 拒答标记：便宜模型答案（小写后）命中任一即判低置信 → 升级。中英混排，覆盖典型措辞。
     */
    private List<String> uncertaintyMarkers = new ArrayList<>(List.of(
            "我不确定", "不确定", "无法确定", "不知道", "无法回答", "没有足够", "资料里没有", "抱歉，我",
            "i'm not sure", "i am not sure", "not sure", "i don't know", "i do not know",
            "cannot answer", "can't answer", "unable to", "insufficient information", "as an ai"));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCheapModel() { return cheapModel; }
    public void setCheapModel(String cheapModel) { this.cheapModel = cheapModel; }

    public String getStrongModel() { return strongModel; }
    public void setStrongModel(String strongModel) { this.strongModel = strongModel; }

    public double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(double confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }

    public int getMinAnswerChars() { return minAnswerChars; }
    public void setMinAnswerChars(int minAnswerChars) { this.minAnswerChars = minAnswerChars; }

    public boolean isSelfRating() { return selfRating; }
    public void setSelfRating(boolean selfRating) { this.selfRating = selfRating; }

    public List<String> getUncertaintyMarkers() { return uncertaintyMarkers; }
    public void setUncertaintyMarkers(List<String> uncertaintyMarkers) { this.uncertaintyMarkers = uncertaintyMarkers; }
}
