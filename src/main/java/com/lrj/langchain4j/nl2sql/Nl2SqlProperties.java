package com.lrj.langchain4j.nl2sql;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code app.nl2sql.*}：自然语言查库（NL2SQL / ChatBI）配置。默认关，
 * 整套 {@code @ConditionalOnProperty(app.nl2sql.enabled)} 不影响现有启动。
 *
 * <pre>
 * app.nl2sql:
 *   enabled: false
 *   datasource:                       # 只读执行库（dev 默认 in-mem H2 + demo 种子）
 *     url: jdbc:h2:mem:nl2sql;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE
 *     admin-username: sa              # 建表 / 种子 / 内省 schema 用（有写权限）
 *     admin-password: ""
 *     readonly-username: nl2sql_ro    # SqlQueryTool 实际执行用（只 GRANT SELECT，L1 护栏）
 *     readonly-password: nl2sql_ro
 *     seed-script: db/nl2sql-demo.sql # classpath；置空 = 不建 demo 库（接真实库时）
 *   max-rows: 1000                    # L4 无 LIMIT 时强制追加的行数
 *   query-timeout-seconds: 5          # L5 statement 超时
 *   allow-tables: [orders, customers, refunds]   # L3 表白名单（也是 SchemaProvider 暴露范围）
 *   tenant-scoped-tables: [orders, customers, refunds]  # L6 必须带 tenant_id 过滤的表
 *   enforce-tenant-predicate: true    # L6 开关；demo 想省事可关
 *   enum-columns:                     # 坑3：把这些列的 distinct 值带进 schema，帮模型用对中文枚举
 *     orders: [status]
 *     refunds: [status]
 * </pre>
 */
@ConfigurationProperties(prefix = "app.nl2sql")
public class Nl2SqlProperties {

    private boolean enabled = false;
    private Datasource datasource = new Datasource();
    private int maxRows = 1000;
    private int queryTimeoutSeconds = 5;
    private List<String> allowTables = new ArrayList<>(List.of("orders", "customers", "refunds"));
    private List<String> tenantScopedTables = new ArrayList<>(List.of("orders", "customers", "refunds"));
    private boolean enforceTenantPredicate = true;
    private Map<String, List<String>> enumColumns = new LinkedHashMap<>();
    /** 自修环上限：单次问答里 run_sql 工具最多调几次（含被护栏拒/执行失败的重试）。防坏 SQL 反复重试烧 token。 */
    private int maxToolCalls = 5;
    /** 数字 grounding（确定性、零 LLM、warn 模式）：核对答案里的数字 ∈ 查询结果，否则末尾追加核对提示。 */
    private boolean numberGrounding = true;

    public static class Datasource {
        /** admin 连接：建库（createDatabaseIfNotExist）/ 建表 / 种子 / 内省 schema（useInformationSchema 让 getColumns 返回列注释）。 */
        private String url = "jdbc:mysql://localhost:3306/nl2sql_demo"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&useInformationSchema=true";
        /**
         * 只读池的连接 url（不带 createDatabaseIfNotExist —— 只读账号无建库权限）。
         * 留空则与 admin 共用 {@code url}。
         */
        private String readonlyUrl = "jdbc:mysql://localhost:3306/nl2sql_demo"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&useInformationSchema=true";
        private String adminUsername = "root";
        private String adminPassword = "";
        private String readonlyUsername = "nl2sql_ro";
        private String readonlyPassword = "nl2sql_ro";
        private String seedScript = "db/nl2sql-demo.sql";

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getReadonlyUrl() { return readonlyUrl; }
        public void setReadonlyUrl(String readonlyUrl) { this.readonlyUrl = readonlyUrl; }
        public String getAdminUsername() { return adminUsername; }
        public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
        public String getAdminPassword() { return adminPassword; }
        public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
        public String getReadonlyUsername() { return readonlyUsername; }
        public void setReadonlyUsername(String readonlyUsername) { this.readonlyUsername = readonlyUsername; }
        public String getReadonlyPassword() { return readonlyPassword; }
        public void setReadonlyPassword(String readonlyPassword) { this.readonlyPassword = readonlyPassword; }
        public String getSeedScript() { return seedScript; }
        public void setSeedScript(String seedScript) { this.seedScript = seedScript; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Datasource getDatasource() { return datasource; }
    public void setDatasource(Datasource datasource) { this.datasource = datasource; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) { this.queryTimeoutSeconds = queryTimeoutSeconds; }
    public List<String> getAllowTables() { return allowTables; }
    public void setAllowTables(List<String> allowTables) { this.allowTables = allowTables; }
    public List<String> getTenantScopedTables() { return tenantScopedTables; }
    public void setTenantScopedTables(List<String> tenantScopedTables) { this.tenantScopedTables = tenantScopedTables; }
    public boolean isEnforceTenantPredicate() { return enforceTenantPredicate; }
    public void setEnforceTenantPredicate(boolean enforceTenantPredicate) { this.enforceTenantPredicate = enforceTenantPredicate; }
    public Map<String, List<String>> getEnumColumns() { return enumColumns; }
    public void setEnumColumns(Map<String, List<String>> enumColumns) { this.enumColumns = enumColumns; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }
    public boolean isNumberGrounding() { return numberGrounding; }
    public void setNumberGrounding(boolean numberGrounding) { this.numberGrounding = numberGrounding; }
}
