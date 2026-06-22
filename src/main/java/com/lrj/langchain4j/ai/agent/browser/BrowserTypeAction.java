package com.lrj.langchain4j.ai.agent.browser;

import com.lrj.langchain4j.ai.agent.AgentAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Browser-use 动作：往当前页面的表单控件里填文本。actionInput 格式 {@code CSS选择器=>要填的文本}
 * （首个 {@code =>} 切分），交给 {@link BrowserSession#type}。页面关闭由 {@link BrowserOpenAction#onRunEnd}
 * 统一负责（共享同一线程会话）。条件化在 {@code app.deep-agent.browser.enabled=true}。
 */
@Component
@ConditionalOnProperty(name = "app.deep-agent.browser.enabled", havingValue = "true")
public class BrowserTypeAction implements AgentAction {

    /** 选择器与文本的分隔符。 */
    static final String SEP = "=>";

    private final BrowserSession session;

    public BrowserTypeAction(BrowserSession session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "browser_type";
    }

    @Override
    public String description() {
        return "在当前页面的输入框/文本域里填文本；actionInput 格式 `CSS选择器" + SEP + "要填的文本`"
                + "（如 `input[name=q]" + SEP + "langchain4j`）。先用 browser_open 打开页面。";
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            return "入参为空：actionInput 格式 `CSS选择器" + SEP + "要填的文本`。";
        }
        int at = input.indexOf(SEP);
        if (at < 0) {
            return "缺少分隔符 '" + SEP + "'：actionInput 格式 `CSS选择器" + SEP + "要填的文本`。";
        }
        String selector = input.substring(0, at).trim();
        String text = input.substring(at + SEP.length());
        if (selector.isBlank()) {
            return "选择器为空：actionInput 格式 `CSS选择器" + SEP + "要填的文本`。";
        }
        return session.type(selector, text);
    }
}
