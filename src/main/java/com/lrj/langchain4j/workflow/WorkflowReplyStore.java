package com.lrj.langchain4j.workflow;

/**
 * 工作流答复（{@code reply}）的持久化存储（#5 大文本进流程变量）。
 *
 * <p><b>为什么不放 Flowable 流程变量</b>：LLM 生成的 {@code reply} 是长文本，若存进流程变量会灌进
 * {@code ACT_RU_VARIABLE}/{@code ACT_HI_VARINST}，跑久了放大历史表膨胀（#4）。这里把 reply 落到独立的
 * 业务表 {@code WF_REPLY}，流程变量只保留 priority/category/summary 这种短的路由/展示字段。
 *
 * <p><b>持久 + 原子</b>：JDBC 实现写在 Flowable 同一个 {@code workflowDataSource} 上、由同一个
 * {@code workflowTransactionManager} 管理。在 ServiceTask 同步执行的事务里写 reply，跟「流程推进 +
 * 人工审批决定」同事务提交——LLM 成功则 reply 与流程态原子落地、重启不丢（正是引入 Flowable 的初衷）。
 *
 * <p>抽成接口便于单测用内存 fake（{@code InMemoryWorkflowReplyStore}）替换，不连 DB。
 */
public interface WorkflowReplyStore {

    /**
     * 写入/覆盖某实例的答复。
     *
     * @param instanceId 流程实例 id
     * @param reply      答复文本（可能很长）
     * @param degraded   是否为 LLM 失败后的降级兜底答复（#3 补偿），供排查/观测
     */
    void save(String instanceId, String reply, boolean degraded);

    /** 读取某实例的答复；无则 null。 */
    String find(String instanceId);

    /** 删除某实例的答复行（历史清理 #4 调用）。 */
    void delete(String instanceId);
}
