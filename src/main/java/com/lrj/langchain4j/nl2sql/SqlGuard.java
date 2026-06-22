package com.lrj.langchain4j.nl2sql;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NL2SQL 的确定性 SQL 安全护栏（L2–L4 + L6，纯逻辑、零外部依赖、无 Spring）。
 * 配合执行层的 L1（只读 DB 账号）和 L5（statement 超时）构成 6 层纵深防御 —— 见 {@code docs/nl2sql.md}。
 *
 * <p>对一条候选 SQL 顺序执行：
 * <ol>
 *   <li><b>L2 单语句 / 只读</b>：去掉末尾 {@code ;} 后不得再有 {@code ;}；必须以 {@code SELECT} 开头；
 *       不得出现写/DDL/越权关键字（{@code insert/update/delete/drop/...}）；不得含注释（{@code --} / {@code /*}）；
 *       不得含 {@code union}（防联合注入）。</li>
 *   <li><b>L3 表白名单</b>：{@code FROM} / {@code JOIN} 引用的表必须全部在 {@code allowTables} 内。</li>
 *   <li><b>L6 租户谓词</b>：引用了 {@code tenantScopedTables} 的表时，SQL 必须带
 *       {@code tenant_id = '<当前租户>'}（由 prompt 注入、guard 强制核对；不符就拒）。</li>
 *   <li><b>L4 强制 LIMIT</b>：原 SQL 无 {@code LIMIT} 时追加 {@code LIMIT maxRows}，防全表扫描。</li>
 * </ol>
 *
 * <p>关键字 / 表名检测在<strong>抹掉字符串字面量</strong>的副本上做，避免把
 * {@code WHERE note = 'shipped from warehouse'} 里的 {@code from warehouse} 误判成表，
 * 或把值 {@code '请尽快 update'} 误判成写操作。租户谓词核对则在保留字面量的原文上做（要看到 {@code 'X'}）。
 *
 * <p>构造参数直接给集合（不依赖 {@link Nl2SqlProperties}），便于纯单元测试；
 * {@code Nl2SqlConfig} 从 props 构建实例。
 */
public class SqlGuard {

    /** 写 / DDL / 越权 / 注入关键字。用 {@code \b} 词边界匹配，故 {@code created_at} / {@code update_time} 这类列名不会误伤。 */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|merge|replace|"
                    + "call|exec|execute|into|union|attach|script|runscript|set|use)\\b");

    /** 抽取 FROM / JOIN 后的表名（可能带 schema. 前缀或引号）。 */
    private static final Pattern TABLE_REF = Pattern.compile(
            "\\b(?:from|join)\\s+([\\w.\"`]+)");

    /** 单引号字符串字面量（不处理转义 ''，对本场景足够）。 */
    private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'");

    private final Set<String> allowTables;
    private final Set<String> tenantScopedTables;
    private final int maxRows;
    private final boolean enforceTenantPredicate;

    public SqlGuard(List<String> allowTables,
                    List<String> tenantScopedTables,
                    int maxRows,
                    boolean enforceTenantPredicate) {
        this.allowTables = lower(allowTables);
        this.tenantScopedTables = lower(tenantScopedTables);
        this.maxRows = maxRows;
        this.enforceTenantPredicate = enforceTenantPredicate;
    }

    public record GuardResult(boolean allowed, String sql, String reason) {
        static GuardResult ok(String sql) { return new GuardResult(true, sql, null); }
        static GuardResult reject(String reason) { return new GuardResult(false, null, reason); }
    }

    /**
     * 校验并（必要时）改写一条候选 SQL。
     *
     * @param rawSql   模型生成的 SQL
     * @param tenantId 当前租户（L6 核对用）
     * @return 通过则 {@code allowed=true} 且 {@code sql} 为最终可执行语句（可能补了 LIMIT）；否则 {@code reason} 说明原因
     */
    public GuardResult check(String rawSql, String tenantId) {
        if (rawSql == null || rawSql.isBlank()) {
            return GuardResult.reject("empty SQL");
        }
        // 去掉首尾空白和单个收尾分号
        String sql = rawSql.strip();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).strip();
        }
        String lower = sql.toLowerCase(Locale.ROOT);

        // L2: 多语句
        if (sql.contains(";")) {
            return GuardResult.reject("multiple statements are not allowed");
        }
        // L2: 注释（可能用于绕过关键字检测）
        if (lower.contains("--") || lower.contains("/*")) {
            return GuardResult.reject("SQL comments are not allowed");
        }
        // 抹掉字符串字面量后再做关键字 / 表名检测
        String scrubbed = STRING_LITERAL.matcher(lower).replaceAll("''");

        // L2: 只读 —— 必须 SELECT 开头
        if (!scrubbed.stripLeading().startsWith("select")) {
            return GuardResult.reject("only SELECT statements are allowed");
        }
        // L2: 禁写 / DDL / 注入关键字
        Matcher kw = FORBIDDEN.matcher(scrubbed);
        if (kw.find()) {
            return GuardResult.reject("forbidden keyword: " + kw.group(1));
        }

        // L3: 表白名单
        Set<String> referenced = referencedTables(scrubbed);
        for (String t : referenced) {
            if (!allowTables.contains(t)) {
                return GuardResult.reject("table not allowed: " + t);
            }
        }

        // L6: 租户谓词
        if (enforceTenantPredicate && tenantId != null && touchesTenantScoped(referenced)) {
            // 归一化 '=' 周围空白后核对 tenant_id='<tenant>'（字面量保留，故用原始 lower）
            String norm = lower.replaceAll("\\s*=\\s*", "=");
            String expected = "tenant_id='" + tenantId.toLowerCase(Locale.ROOT) + "'";
            if (!norm.contains(expected)) {
                return GuardResult.reject("missing tenant filter (require " + expected + ")");
            }
        }

        // L4: 强制 LIMIT（仅在原 SQL 没写时追加）
        String finalSql = sql;
        if (!Pattern.compile("\\blimit\\b").matcher(scrubbed).find()) {
            finalSql = sql + " LIMIT " + maxRows;
        }
        return GuardResult.ok(finalSql);
    }

    private Set<String> referencedTables(String scrubbed) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher m = TABLE_REF.matcher(scrubbed);
        while (m.find()) {
            String raw = m.group(1).replace("\"", "").replace("`", "");
            int dot = raw.lastIndexOf('.');     // schema.table → table
            String name = dot >= 0 ? raw.substring(dot + 1) : raw;
            if (!name.isBlank()) {
                tables.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    private boolean touchesTenantScoped(Set<String> referenced) {
        for (String t : referenced) {
            if (tenantScopedTables.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> lower(List<String> in) {
        Set<String> s = new LinkedHashSet<>();
        if (in != null) {
            for (String x : in) {
                if (x != null && !x.isBlank()) {
                    s.add(x.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return s;
    }
}
