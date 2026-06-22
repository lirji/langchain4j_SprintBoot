package com.lrj.langchain4j.workflow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯逻辑单测：历史清理的保留期分界点计算。不连 Flowable / DB。
 */
class WorkflowHistoryCleanerTest {

    @Test
    void cutoff_isNowMinusRetention() {
        Instant now = Instant.parse("2026-06-02T12:00:00Z");
        Date cutoff = WorkflowHistoryCleaner.cutoff(now, Duration.ofDays(30));
        assertEquals(Date.from(now.minus(Duration.ofDays(30))), cutoff);
    }

    @Test
    void cutoff_shorterRetentionIsMoreRecent() {
        Instant now = Instant.parse("2026-06-02T12:00:00Z");
        Date thirtyDays = WorkflowHistoryCleaner.cutoff(now, Duration.ofDays(30));
        Date sevenDays = WorkflowHistoryCleaner.cutoff(now, Duration.ofDays(7));
        assertTrue(sevenDays.after(thirtyDays), "保留期越短，分界点越靠近现在（删得越少）");
    }
}
