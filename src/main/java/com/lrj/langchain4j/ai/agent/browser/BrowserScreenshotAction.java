package com.lrj.langchain4j.ai.agent.browser;

import com.lrj.langchain4j.ai.agent.AgentAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Browser-use 动作：截当前页面整页图。actionInput 忽略。返回保存路径 + 字节数（不回传 base64，
 * 避免撑爆 scratchpad）——后续若要让模型「看」截图，再走 {@code ai/vision} 的 caption/answer。
 * 页面关闭由 {@link BrowserOpenAction#onRunEnd} 统一负责。条件化在 {@code app.deep-agent.browser.enabled=true}。
 */
@Component
@ConditionalOnProperty(name = "app.deep-agent.browser.enabled", havingValue = "true")
public class BrowserScreenshotAction implements AgentAction {

    private final BrowserSession session;

    public BrowserScreenshotAction(BrowserSession session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "browser_screenshot";
    }

    @Override
    public String description() {
        return "截当前页面整页图存到文件，返回保存路径（actionInput 留空）。先用 browser_open 打开页面。";
    }

    @Override
    public String run(String input) {
        return session.screenshot();
    }
}
