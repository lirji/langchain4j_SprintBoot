package com.lrj.langchain4j.ai.agent.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link BrowserSession} 的 Playwright 实现：无头 Chromium。<strong>按线程懒加载</strong>——
 * 每个执行 run 的线程独立持有 Playwright+Browser+Page（线程隔离，规避 Playwright 非线程安全问题），
 * run 结束由 {@code closeForThread} 关闭。条件化在 {@code app.deep-agent.browser.enabled=true}：
 * 关闭时本 Bean 与 browser 动作全不装配，Playwright 不被触碰、Chromium 不下载。
 *
 * <p>v1 能力：导航 + 按链接文本点击 + 读取渲染后文本（相对纯 HTTP fetch 的价值在于<strong>执行 JS</strong>）。
 * 表单输入 / 截图 / 坐标点击 = 未来项。首次使用需先装 Chromium 二进制（见 pom 注释 / docs）。
 */
@Component
@ConditionalOnProperty(name = "app.deep-agent.browser.enabled", havingValue = "true")
public class PlaywrightBrowserSession implements BrowserSession {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserSession.class);

    /** 观察文本截断上限，挡整页 innerText 撑爆 prompt。 */
    private static final int MAX_TEXT_CHARS = 2000;
    /** 列出的链接条数上限。 */
    private static final int MAX_LINKS = 25;
    private static final double NAV_TIMEOUT_MS = 15_000;

    /** 每线程一套，避免跨线程共享 Playwright 对象（非线程安全）。 */
    private final ThreadLocal<Holder> holder = new ThreadLocal<>();

    private record Holder(Playwright playwright, Browser browser, Page page) {}

    private Page page() {
        Holder h = holder.get();
        if (h == null) {
            Playwright pw = Playwright.create();
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setDefaultTimeout(NAV_TIMEOUT_MS);
            h = new Holder(pw, browser, page);
            holder.set(h);
        }
        return h.page();
    }

    @Override
    public String open(String url) {
        if (url == null || url.isBlank()) return "no url given";
        String target = url.trim();
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            target = "https://" + target;
        }
        try {
            Page page = page();
            page.navigate(target);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            return render(page);
        } catch (Exception e) {
            return "navigation failed for '" + target + "': " + e.getMessage();
        }
    }

    @Override
    public String click(String linkText) {
        if (linkText == null || linkText.isBlank()) return "no link text given";
        Holder h = holder.get();
        if (h == null) return "no page open yet; use browser_open first";
        String needle = linkText.trim().toLowerCase();
        try {
            Page page = h.page();
            for (ElementHandle a : page.querySelectorAll("a")) {
                String txt = safe(a.innerText());
                if (!txt.isBlank() && txt.toLowerCase().contains(needle)) {
                    a.click();
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    return render(page);
                }
            }
            return "no link matching '" + linkText + "'. " + linksLine(page);
        } catch (Exception e) {
            return "click failed for '" + linkText + "': " + e.getMessage();
        }
    }

    @Override
    public void closeForThread() {
        Holder h = holder.get();
        if (h == null) return;
        holder.remove();
        try {
            h.browser().close();
        } catch (Exception e) {
            log.debug("browser close failed: {}", e.toString());
        }
        try {
            h.playwright().close();
        } catch (Exception e) {
            log.debug("playwright close failed: {}", e.toString());
        }
    }

    // -------- rendering --------

    private static String render(Page page) {
        StringBuilder sb = new StringBuilder();
        sb.append("title: ").append(safe(page.title())).append('\n');
        sb.append("url: ").append(safe(page.url())).append('\n');
        String text = safe(page.innerText("body")).replaceAll("\\n{3,}", "\n\n").trim();
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS) + "…(truncated)";
        }
        sb.append("text:\n").append(text).append('\n');
        sb.append(linksLine(page));
        return sb.toString();
    }

    private static String linksLine(Page page) {
        List<ElementHandle> anchors = page.querySelectorAll("a");
        StringBuilder sb = new StringBuilder("links: ");
        int shown = 0;
        for (ElementHandle a : anchors) {
            String txt = safe(a.innerText()).replaceAll("\\s+", " ").trim();
            if (txt.isBlank()) continue;
            if (shown > 0) sb.append(" | ");
            sb.append('"').append(txt.length() > 40 ? txt.substring(0, 40) : txt).append('"');
            if (++shown >= MAX_LINKS) break;
        }
        if (shown == 0) sb.append("(none)");
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
