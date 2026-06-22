package com.lrj.langchain4j.ai.agent.browser;

import com.lrj.langchain4j.ai.agent.AgentAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Browser-use 动作：按像素坐标点击。`browser_click` 靠链接文本匹配，遇到图标/canvas/无文字按钮就抓瞎；
 * 这个动作补上「按坐标点」——配合 `browser_see`（视觉模型读出元素大致位置）形成 computer-use 式的
 * 「看坐标 → 点坐标」闭环。actionInput 格式 {@code x,y}（像素，逗号分隔），交给 {@link BrowserSession#clickAt}。
 *
 * <p>页面关闭由 {@link BrowserOpenAction#onRunEnd} 统一负责（共享同一线程会话）。
 * 条件化在 {@code app.deep-agent.browser.enabled=true}。
 */
@Component
@ConditionalOnProperty(name = "app.deep-agent.browser.enabled", havingValue = "true")
public class BrowserClickXyAction implements AgentAction {

    private final BrowserSession session;

    public BrowserClickXyAction(BrowserSession session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "browser_click_xy";
    }

    @Override
    public String description() {
        return "按像素坐标点击当前页面；actionInput 格式 `x,y`（如 `120,340`）。"
                + "链接文本点不到（图标/canvas/无文字按钮）时用，可配合 browser_see 读出位置。先 browser_open 打开页面。";
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            return "入参为空：actionInput 格式 `x,y`（如 `120,340`）。";
        }
        String[] parts = input.trim().split(",", 2);
        if (parts.length != 2) {
            return "格式应为 `x,y`（逗号分隔两个数）；收到：" + input.trim();
        }
        double x;
        double y;
        try {
            x = Double.parseDouble(parts[0].trim());
            y = Double.parseDouble(parts[1].trim());
        } catch (NumberFormatException e) {
            return "坐标必须是数字；收到：" + input.trim();
        }
        return session.clickAt(x, y);
    }
}
