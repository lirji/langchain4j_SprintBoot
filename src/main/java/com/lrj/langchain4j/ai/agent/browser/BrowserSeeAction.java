package com.lrj.langchain4j.ai.agent.browser;

import com.lrj.langchain4j.ai.agent.AgentAction;
import com.lrj.langchain4j.ai.vision.VisionModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Browser-use 动作：截当前页面图并用<strong>视觉模型</strong>理解——让深度 Agent 能「看」页面，
 * 而不只读 {@code browser_open} 抽出的纯文本（图表/布局/验证码/纯图片页等文本抽不出的内容）。
 * 串联刚落地的 {@code browser_screenshot}（出截图）与 {@code ai/vision}（看图），闭合「截图 → 理解」回路。
 *
 * <p><strong>仅 {@code app.deep-agent.browser.enabled} 且 {@code app.vision.enabled} 同时为 true 时装配</strong>
 * （多 property 的 {@code @ConditionalOnProperty} 要求全部命中）——视觉模型关闭时这个动作不出现在清单里。
 * 视觉 {@code ChatModel} 已挂指标 + per-tenant token 预算 listener（见 {@code VisionConfig}），所以这步的
 * 视觉调用 token 也正确纳入配额。
 */
@Component
@ConditionalOnProperty(name = {"app.deep-agent.browser.enabled", "app.vision.enabled"}, havingValue = "true")
public class BrowserSeeAction implements AgentAction {

    private static final String PNG = "image/png";

    private final BrowserSession session;
    private final VisionModel vision;

    public BrowserSeeAction(BrowserSession session, VisionModel vision) {
        this.session = session;
        this.vision = vision;
    }

    @Override
    public String name() {
        return "browser_see";
    }

    @Override
    public String description() {
        return "截当前页面图并用视觉模型「看」它；actionInput 填想问的问题（留空则整体描述页面）。"
                + "页面文本抽不出的内容（图表/布局/纯图片）用它。先用 browser_open 打开页面。";
    }

    @Override
    public String run(String input) {
        byte[] image = session.screenshotBytes();
        if (image == null || image.length == 0) {
            return "no page open yet; use browser_open first（或截图为空）";
        }
        try {
            return (input == null || input.isBlank())
                    ? vision.caption(image, PNG)
                    : vision.answer(image, PNG, input.trim());
        } catch (Exception e) {
            return "视觉理解失败：" + e.getMessage() + "（可改用 browser_open 读文本，或换问法重试）";
        }
    }
}
