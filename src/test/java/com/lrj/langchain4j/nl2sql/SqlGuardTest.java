package com.lrj.langchain4j.nl2sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlGuard 是 NL2SQL 唯一的确定性安全边界 —— 注入 / 越权 / 全表扫描全靠它兜，
 * 也是最该回归的纯逻辑层（对齐 CLAUDE.md「LLM 行为走 eval，确定性逻辑走 JUnit」）。
 *
 * <p>白名单：orders / customers / refunds（均 tenant-scoped）；maxRows=1000；enforceTenant=true。
 */
class SqlGuardTest {

    private final SqlGuard guard = new SqlGuard(
            List.of("orders", "customers", "refunds"),
            List.of("orders", "customers", "refunds"),
            1000,
            true);

    private static final String T = "tenanta";

    private SqlGuard.GuardResult check(String sql) {
        return guard.check(sql, T);
    }

    // ---------- 放行 + 改写 ----------

    @Test
    void plainSelect_passes_andGetsLimitAppended() {
        var r = check("SELECT id, amount FROM refunds WHERE tenant_id = 'tenanta'");
        assertThat(r.allowed()).isTrue();
        assertThat(r.sql()).endsWith("LIMIT 1000");
    }

    @Test
    void existingLimit_isPreserved_notDoubled() {
        var r = check("SELECT * FROM orders WHERE tenant_id='tenanta' LIMIT 5");
        assertThat(r.allowed()).isTrue();
        assertThat(r.sql()).isEqualTo("SELECT * FROM orders WHERE tenant_id='tenanta' LIMIT 5");
    }

    @Test
    void trailingSemicolon_isStripped_notRejected() {
        var r = check("SELECT count(*) FROM orders WHERE tenant_id = 'tenanta';");
        assertThat(r.allowed()).isTrue();
        assertThat(r.sql()).doesNotContain(";");
    }

    @Test
    void aggregateWithJoin_passes_whenBothTablesWhitelistedAndTenantFiltered() {
        var r = check("SELECT c.name, sum(r.amount) FROM refunds r "
                + "JOIN customers c ON r.customer_id = c.id "
                + "WHERE r.tenant_id = 'tenanta' AND c.tenant_id = 'tenanta' GROUP BY c.name");
        assertThat(r.allowed()).isTrue();
    }

    @Test
    void columnNamesContainingKeywords_areNotFalsePositives() {
        // created_at / update_time / offset 都含被禁关键字的子串，但词边界不该误伤
        var r = check("SELECT created_at, update_time FROM orders "
                + "WHERE tenant_id = 'tenanta' ORDER BY created_at LIMIT 10 OFFSET 5");
        assertThat(r.allowed()).isTrue();
    }

    @Test
    void keywordInsideStringLiteral_isNotTreatedAsWrite() {
        var r = check("SELECT id FROM orders WHERE tenant_id = 'tenanta' AND note = 'please update asap'");
        assertThat(r.allowed()).isTrue();
    }

    // ---------- L2 只读 / 单语句 / 注释 ----------

    @Test
    void multipleStatements_rejected() {
        var r = check("SELECT * FROM orders WHERE tenant_id='tenanta'; DROP TABLE orders");
        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).contains("multiple statements");
    }

    @Test
    void nonSelect_rejected() {
        assertThat(check("UPDATE orders SET amount = 0 WHERE tenant_id='tenanta'").allowed()).isFalse();
        assertThat(check("DELETE FROM orders WHERE tenant_id='tenanta'").allowed()).isFalse();
    }

    @Test
    void writeKeyword_rejected() {
        var r = check("SELECT * FROM orders WHERE tenant_id='tenanta' AND id IN (DELETE FROM orders)");
        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).contains("forbidden keyword");
    }

    @Test
    void unionInjection_rejected() {
        var r = check("SELECT id FROM orders WHERE tenant_id='tenanta' UNION SELECT password FROM customers");
        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).contains("forbidden keyword: union");
    }

    @Test
    void comments_rejected() {
        assertThat(check("SELECT * FROM orders WHERE tenant_id='tenanta' -- bypass").allowed()).isFalse();
        assertThat(check("SELECT * FROM orders /* x */ WHERE tenant_id='tenanta'").allowed()).isFalse();
    }

    // ---------- L3 表白名单 ----------

    @Test
    void tableNotInWhitelist_rejected() {
        var r = check("SELECT * FROM users WHERE tenant_id='tenanta'");
        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).contains("table not allowed: users");
    }

    @Test
    void systemTableAccess_rejected() {
        var r = check("SELECT * FROM information_schema.tables WHERE tenant_id='tenanta'");
        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).contains("table not allowed");
    }

    // ---------- L6 租户谓词 ----------

    @Test
    void missingTenantFilter_rejected() {
        var r = check("SELECT * FROM orders");
        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).contains("missing tenant filter");
    }

    @Test
    void wrongTenantInFilter_rejected() {
        var r = check("SELECT * FROM orders WHERE tenant_id = 'someone_else'");
        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).contains("missing tenant filter");
    }

    @Test
    void tenantFilter_toleratesSpacingVariants() {
        assertThat(check("SELECT * FROM orders WHERE tenant_id='tenanta'").allowed()).isTrue();
        assertThat(check("SELECT * FROM orders WHERE tenant_id   =   'tenanta'").allowed()).isTrue();
    }

    @Test
    void enforceTenantOff_allowsUnscopedQuery() {
        SqlGuard lax = new SqlGuard(List.of("orders"), List.of("orders"), 1000, false);
        assertThat(lax.check("SELECT * FROM orders", T).allowed()).isTrue();
    }

    @Test
    void blankSql_rejected() {
        assertThat(check("   ").allowed()).isFalse();
    }
}
