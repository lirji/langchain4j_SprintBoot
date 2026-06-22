package com.lrj.langchain4j.ai.agent.browser;

import com.lrj.langchain4j.ai.agent.AgentAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Browser-use 动作：在当前页面点击文本匹配的链接，返回跳转后的页面。条件化在
 * {@code app.deep-agent.browser.enabled=true}。页面关闭由 {@link BrowserOpenAction#onRunEnd} 统一负责
 * （二者共享同一线程会话）。
 */
@Component
@ConditionalOnProperty(name = "app.deep-agent.browser.enabled", havingValue = "true")
public class BrowserClickAction implements AgentAction {

    private final BrowserSession session;

    public BrowserClickAction(BrowserSession session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "browser_click";
    }

    @Override
    public String description() {
        return "点击当前页面上文本包含给定串的第一个链接；actionInput 填链接文本（用 browser_open 返回的链接列表里的文本）";
    }

    @Override
    public String run(String input) {
        return session.click(input);
    }
}
