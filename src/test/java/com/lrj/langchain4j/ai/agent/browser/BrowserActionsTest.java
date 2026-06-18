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
        int closed = 0;
        @Override public String open(String url) { opened.add(url); return "opened:" + url; }
        @Override public String click(String text) { clicked.add(text); return "clicked:" + text; }
        @Override public void closeForThread() { closed++; }
    }

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
}
