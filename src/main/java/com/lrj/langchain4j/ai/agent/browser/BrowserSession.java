package com.lrj.langchain4j.ai.agent.browser;

/**
 * 浏览器会话抽象：给深度 Agent 的 Browser-use 动作用。做成接口，方便单测注入假实现
 * （真 Playwright 需下载 Chromium 二进制、连网，不能进纯 JVM 单测）。
 *
 * <p>实现按<strong>线程</strong>维护会话——深度 Agent 循环在单线程内顺序执行多步，
 * 同一 run 的 open→click→read 复用同一页面；run 结束由 {@code onRunEnd} 关闭。
 */
public interface BrowserSession {

    /** 导航到 URL，返回渲染后的页面观察（标题 + 可见文本截断 + 编号链接列表）。 */
    String open(String url);

    /** 点击当前页面上文本匹配（包含）{@code linkText} 的第一个链接，返回新页面观察。 */
    String click(String linkText);

    /** 关闭当前线程的会话（释放页面/上下文/浏览器）。幂等。 */
    void closeForThread();
}
