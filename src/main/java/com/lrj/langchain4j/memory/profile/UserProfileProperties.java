package com.lrj.langchain4j.memory.profile;

/**
 * {@code app.memory.profile.*} 绑定。默认关 → 长期记忆相关 Bean 全不装配，对话行为不变。
 */
public class UserProfileProperties {

    /** 总开关。 */
    private boolean enabled = false;
    /** 存储：{@code in-memory}（默认，重启即丢）| {@code redis}（持久化升级路径）。 */
    private String store = "in-memory";
    /** 每用户记忆上限，超出淘汰最旧。 */
    private int maxItems = 50;
    /** chat 前注入时召回最近多少条。 */
    private int recallLimit = 12;
    /** 观察（抽取+入库）是否异步，默认 true，不阻塞 chat 响应。 */
    private boolean async = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStore() { return store; }
    public void setStore(String store) { this.store = store; }
    public int getMaxItems() { return maxItems; }
    public void setMaxItems(int maxItems) { this.maxItems = maxItems; }
    public int getRecallLimit() { return recallLimit; }
    public void setRecallLimit(int recallLimit) { this.recallLimit = recallLimit; }
    public boolean isAsync() { return async; }
    public void setAsync(boolean async) { this.async = async; }
}
