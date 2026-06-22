package com.lrj.langchain4j.ai.agent;

/**
 * 可选生命周期钩子：一个 {@link AgentAction} 若实现它，{@link DeepAgentService} 会在<strong>顶层
 * run（depth=0）结束后</strong>回调 {@code onRunEnd()}——用于释放跨步持有的资源（如 Browser-use
 * 的浏览器页面）。子 Agent 派生（depth>0）不触发，资源在整条 run 结束时统一清理。
 *
 * <p>回调跑在执行该 run 的同一线程上（同步端点=请求线程；异步端点=worker 线程），所以基于
 * ThreadLocal 的会话能正确关闭对应线程的实例。
 */
public interface AgentRunListener {
    void onRunEnd();
}
