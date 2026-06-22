package com.lrj.langchain4j.ai.agent.browser;

import com.lrj.langchain4j.ai.agent.AgentAction;
import com.lrj.langchain4j.ai.agent.AgentRunListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Browser-use 动作：导航到一个 URL 并返回渲染后的页面（含 JS 执行结果）。条件化在
 * {@code app.deep-agent.browser.enabled=true}。实现 {@link AgentRunListener}，run 结束时关页面。
 */
@Component
@ConditionalOnProperty(name = "app.deep-agent.browser.enabled", havingValue = "true")
public class BrowserOpenAction implements AgentAction, AgentRunListener {

    private final BrowserSession session;

    public BrowserOpenAction(BrowserSession session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "browser_open";
    }

    @Override
    public String description() {
        return "用无头浏览器打开一个网址（会执行页面 JS）；actionInput 填 URL。返回标题、可见文本、可点击的链接列表";
    }

    @Override
    public String run(String input) {
        return session.open(input);
    }

    @Override
    public void onRunEnd() {
        session.closeForThread();
    }
}
