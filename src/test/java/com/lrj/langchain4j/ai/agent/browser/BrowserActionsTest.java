package com.lrj.langchain4j.ai.agent.browser;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Browser-use 动作的确定性单测（不连真浏览器）：用假 {@link BrowserSession} 验证动作把入参透传给会话、
 * 回传观察、且 onRunEnd 关闭会话。真 Playwright 渲染需 Chromium 二进制 + 联网，不进纯 JVM 单测。
 */
class BrowserActionsTest {

    /** 记录调用的假会话。 */
    static class FakeSession implements BrowserSession {
        final List<String> opened = new ArrayList<>();
        final List<String> clicked = new ArrayList<>();
        final List<String> clickedXy = new ArrayList<>();
        final List<String> typed = new ArrayList<>();
        int shots = 0;
        int closed = 0;
        @Override public String open(String url) { opened.add(url); return "opened:" + url; }
        @Override public String click(String text) { clicked.add(text); return "clicked:" + text; }
        @Override public String clickAt(double x, double y) { clickedXy.add(x + "," + y); return "clickedAt:" + x + "," + y; }
        @Override public String type(String selector, String text) {
            typed.add(selector + "|" + text);
            return "typed:" + selector + "=" + text;
        }
        @Override public String screenshot() { shots++; return "shot#" + shots; }
        @Override public byte[] screenshotBytes() { return pngBytes; }
        @Override public void closeForThread() { closed++; }
    }

    /** 记录调用的假视觉模型。 */
    static class FakeVision implements com.lrj.langchain4j.ai.vision.VisionModel {
        String lastQuestion;
        int captions = 0;
        @Override public String caption(byte[] image, String mime) { captions++; return "caption-of-" + image.length + "B"; }
        @Override public String answer(byte[] image, String mime, String question) { lastQuestion = question; return "answer:" + question; }
    }

    private static byte[] pngBytes = new byte[]{1, 2, 3, 4};

    @Test
    void openAction_delegatesToSession() {
        FakeSession s = new FakeSession();
        BrowserOpenAction open = new BrowserOpenAction(s);
        assertEquals("browser_open", open.name());
        assertTrue(open.description().contains("URL") || open.description().contains("网址"));

        String obs = open.run("example.com");
        assertEquals("opened:example.com", obs);
        assertEquals(List.of("example.com"), s.opened);
    }

    @Test
    void clickAction_delegatesToSession() {
        FakeSession s = new FakeSession();
        BrowserClickAction click = new BrowserClickAction(s);
        assertEquals("browser_click", click.name());

        String obs = click.run("Docs");
        assertEquals("clicked:Docs", obs);
        assertEquals(List.of("Docs"), s.clicked);
    }

    @Test
    void openAction_onRunEnd_closesSession() {
        FakeSession s = new FakeSession();
        BrowserOpenAction open = new BrowserOpenAction(s);
        open.onRunEnd();
        assertEquals(1, s.closed, "onRunEnd must release the per-thread browser session");
    }

    @Test
    void clickXyAction_parsesCoordinates() {
        FakeSession s = new FakeSession();
        BrowserClickXyAction click = new BrowserClickXyAction(s);
        assertEquals("browser_click_xy", click.name());

        String obs = click.run("120, 340");
        assertEquals("clickedAt:120.0,340.0", obs);
        assertEquals(List.of("120.0,340.0"), s.clickedXy);
    }

    @Test
    void clickXyAction_nonNumeric_isCorrectable() {
        FakeSession s = new FakeSession();
        String obs = new BrowserClickXyAction(s).run("left,top");
        assertTrue(obs.contains("数字"), "非数字坐标应返回可纠错提示而非透传");
        assertTrue(s.clickedXy.isEmpty());
    }

    @Test
    void typeAction_splitsSelectorAndText() {
        FakeSession s = new FakeSession();
        BrowserTypeAction type = new BrowserTypeAction(s);
        assertEquals("browser_type", type.name());

        String obs = type.run("input[name=q]=>langchain4j");
        assertEquals("typed:input[name=q]=langchain4j", obs);
        assertEquals(List.of("input[name=q]|langchain4j"), s.typed);
    }

    @Test
    void typeAction_missingSeparator_isCorrectable() {
        FakeSession s = new FakeSession();
        String obs = new BrowserTypeAction(s).run("just text no selector");
        assertTrue(obs.contains("分隔符"), "缺少 => 应返回可纠错提示而非透传给会话");
        assertTrue(s.typed.isEmpty());
    }

    @Test
    void screenshotAction_delegatesToSession() {
        FakeSession s = new FakeSession();
        BrowserScreenshotAction shot = new BrowserScreenshotAction(s);
        assertEquals("browser_screenshot", shot.name());
        assertEquals("shot#1", shot.run(""));
        assertEquals(1, s.shots);
    }

    @Test
    void seeAction_blankInput_captionsTheScreenshot() {
        FakeSession s = new FakeSession();
        FakeVision v = new FakeVision();
        BrowserSeeAction see = new BrowserSeeAction(s, v);
        assertEquals("browser_see", see.name());

        String obs = see.run("");
        assertEquals("caption-of-4B", obs, "留空应走 caption 整体描述截图字节");
        assertEquals(1, v.captions);
    }

    @Test
    void seeAction_withQuestion_answersAboutScreenshot() {
        FakeVision v = new FakeVision();
        String obs = new BrowserSeeAction(new FakeSession(), v).run("页面上有几个按钮？");
        assertEquals("answer:页面上有几个按钮？", obs, "有问题应走 answer");
        assertEquals("页面上有几个按钮？", v.lastQuestion);
    }

    @Test
    void seeAction_noPage_isCorrectable() {
        FakeSession empty = new FakeSession() {
            @Override public byte[] screenshotBytes() { return new byte[0]; }
        };
        String obs = new BrowserSeeAction(empty, new FakeVision()).run("");
        assertTrue(obs.contains("no page open"), "截图为空应返回可纠错提示而非调视觉模型");
    }
}
