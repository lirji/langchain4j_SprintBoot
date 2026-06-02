package com.lrj.langchain4j.workflow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯逻辑单测：超时分界点计算。不拉 Spring context、不连 Flowable / 模型。
 */
class ApprovalTimeoutSweeperTest {

    @Test
    void cutoff_isNowMinusTimeout() {
        Instant now = Instant.parse("2026-06-02T12:00:00Z");
        Date cutoff = ApprovalTimeoutSweeper.cutoff(now, Duration.ofHours(24));
        assertEquals(Date.from(Instant.parse("2026-06-01T12:00:00Z")), cutoff);
    }

    @Test
    void cutoff_shortTimeout_isInThePast() {
        Instant now = Instant.now();
        Date cutoff = ApprovalTimeoutSweeper.cutoff(now, Duration.ofSeconds(20));
        assertTrue(cutoff.toInstant().isBefore(now), "cutoff 必须早于 now，超时窗口才有意义");
        assertEquals(Date.from(now.minusSeconds(20)), cutoff);
    }
}
