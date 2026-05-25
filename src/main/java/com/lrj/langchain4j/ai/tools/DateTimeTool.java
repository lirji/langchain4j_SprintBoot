package com.lrj.langchain4j.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

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
        ZoneId zone = (zoneId == null || zoneId.isBlank()) ? ZoneId.of("Asia/Shanghai") : ZoneId.of(zoneId);
        return LocalDateTime.now(zone) + " (" + zone + ")";
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
    public long daysUntil(@P("Target date in yyyy-MM-dd format") String isoDate) {
        try {
            return ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(isoDate));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "isoDate must be yyyy-MM-dd (got: " + isoDate + ")", e);
        }
    }
}
