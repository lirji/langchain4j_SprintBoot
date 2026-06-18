package com.lrj.langchain4j.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 时间工具。<strong>错误处理约定（与 {@code SqlQueryTool} 一致）</strong>：非法入参<strong>返回可纠错的
 * 错误文本，不抛异常</strong> —— 抛异常会中断整个 chat 回合（模型拿不到反馈、用户拿到 500）；
 * 返回错误文本则让模型在下一个工具回合自行改写重试（如把 {@code GMT+8} 改成 {@code Asia/Shanghai}）。
 */
@Component
public class DateTimeTool {

    @Tool("""
            Return the current wall-clock date and time in a given IANA time zone.

            Use this whenever the user asks about the current time, today's date,
            "现在几点 / what time is it / what's today's date / what day of the week is it",
            or when answering a follow-up question would require knowing "now"
            (e.g. "how many days until ..." — call `daysUntil` instead).

            Do NOT call this if the user has explicitly stated a time / date in their
            message, or if the question is hypothetical.

            Parameter `zoneId` MUST be an IANA zone id such as `Asia/Shanghai`, `UTC`,
            `Europe/Paris`, `America/New_York`. Do NOT pass aliases like `GMT+8`,
            `CST`, `北京时间`, or a numeric offset — convert to the canonical IANA id
            first. If the user did not specify a zone, default to `Asia/Shanghai`.
            """)
    public String currentDateTime(@P("IANA time zone id, e.g. Asia/Shanghai") String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            zoneId = "Asia/Shanghai";
        }
        try {
            ZoneId zone = ZoneId.of(zoneId);
            return LocalDateTime.now(zone) + " (" + zone + ")";
        } catch (DateTimeException e) {
            // 坏时区（GMT+8 / CST / 北京时间 等）→ 返回可纠错文本，让模型换成 IANA id 再调
            return "Invalid zoneId '" + zoneId + "': not a valid IANA zone id. "
                    + "Use a canonical id like Asia/Shanghai, UTC, Europe/Paris, America/New_York "
                    + "(not aliases like GMT+8 / CST / 北京时间). Call this tool again with a corrected zoneId.";
        }
    }

    @Tool("""
            Return the integer number of days from today (system date) until the given
            ISO-8601 date. Positive when the target is in the future, negative when
            past, 0 if it is today.

            Use this for "距离 X 还有多少天 / how many days until / 距离 X 过了多少天".
            Do NOT use it to compute durations between two arbitrary dates that don't
            involve "today" — for that, do the arithmetic yourself in the answer.

            Parameter `isoDate` MUST be in `yyyy-MM-dd` format (e.g. `2026-12-31`).
            Convert natural-language dates ("明年春节", "下周五") to ISO before calling.
            """)
    public String daysUntil(@P("Target date in yyyy-MM-dd format") String isoDate) {
        try {
            long days = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(isoDate));
            return String.valueOf(days);
        } catch (DateTimeParseException e) {
            // 坏日期格式 → 返回可纠错文本，让模型把自然语言日期先转成 ISO 再调
            return "Invalid isoDate '" + isoDate + "': must be yyyy-MM-dd (e.g. 2026-12-31). "
                    + "Convert natural-language dates to ISO format first, then call this tool again.";
        }
    }
}
