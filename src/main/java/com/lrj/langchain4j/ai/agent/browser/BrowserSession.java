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

    /** 在像素坐标 {@code (x, y)} 处点击（链接文本匹配不到时的兜底，如图标/canvas/无文字按钮），返回页面观察。 */
    String clickAt(double x, double y);

    /** 在 CSS 选择器 {@code selector} 命中的表单控件里填入 {@code text}，返回当前页面观察。 */
    String type(String selector, String text);

    /** 截当前页面整页图存到临时文件，返回保存路径 + 字节数（不回传 base64，避免撑爆 scratchpad）。 */
    String screenshot();

    /** 截当前页面整页图，返回原始 PNG 字节（供 {@code browser_see} 喂视觉模型）。无页面时返回空数组。 */
    byte[] screenshotBytes();

    /** 关闭当前线程的会话（释放页面/上下文/浏览器）。幂等。 */
    void closeForThread();
}
