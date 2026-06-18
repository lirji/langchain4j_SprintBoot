package com.lrj.langchain4j.nl2sql;

import com.lrj.langchain4j.audit.AuditLogger;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * NL2SQL 装配。整体 {@code @ConditionalOnProperty(app.nl2sql.enabled)}，默认关 = 零开销、不影响现有启动。
 *
 * <p>只注册一个 {@link DataSource} bean（admin，用于建表 / 种子 / schema 内省）。
 * 只读执行用的第二个连接池（L1：只读 DB 账号）在 {@link #nl2sqlReadOnlyJdbc} 里就地构建，
 * <strong>不注册为 bean</strong> —— 避免出现两个 DataSource bean 引起注入歧义，也对齐
 * {@code SqlQueryTool} "不进 Spring 容器" 的同款隔离思路。
 */
@Configuration
@ConditionalOnProperty(name = "app.nl2sql.enabled", havingValue = "true")
@EnableConfigurationProperties(Nl2SqlProperties.class)
public class Nl2SqlConfig {

    private static final Logger log = LoggerFactory.getLogger(Nl2SqlConfig.class);

    /** admin 连接：建表 / 种子 / 内省 schema。dev 默认指向 in-mem H2 + demo 种子。 */
    @Bean
    public DataSource nl2sqlAdminDataSource(Nl2SqlProperties props) {
        Nl2SqlProperties.Datasource d = props.getDatasource();
        HikariDataSource ds = pool(d.getUrl(), d.getAdminUsername(), d.getAdminPassword(), false, "nl2sql-admin");
        if (d.getSeedScript() != null && !d.getSeedScript().isBlank()) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(d.getSeedScript()));
            populator.execute(ds);
            log.info("NL2SQL demo 库已用 classpath:{} 初始化（含只读账号 {}）", d.getSeedScript(), d.getReadonlyUsername());
        }
        return ds;
    }

    /**
     * 只读执行的 JdbcTemplate。绑定只读 DB 账号（L1）+ statement 超时（L5）。
     * 入参 {@code nl2sqlAdminDataSource} 仅用于强制构建顺序（种子 + 只读账号必须先就绪）。
     */
    @Bean
    public JdbcTemplate nl2sqlReadOnlyJdbc(Nl2SqlProperties props, DataSource nl2sqlAdminDataSource) {
        Nl2SqlProperties.Datasource d = props.getDatasource();
        HikariDataSource readOnly = pool(readOnlyUrl(d), d.getReadonlyUsername(), d.getReadonlyPassword(), true, "nl2sql-ro");
        JdbcTemplate jdbc = new JdbcTemplate(readOnly);
        jdbc.setQueryTimeout(props.getQueryTimeoutSeconds());
        return jdbc;
    }

    /** 只读池 url：显式配了就用（默认不带 createDatabaseIfNotExist，只读账号无建库权限）；否则共用 admin url。 */
    private static String readOnlyUrl(Nl2SqlProperties.Datasource d) {
        if (d.getReadonlyUrl() != null && !d.getReadonlyUrl().isBlank()) {
            return d.getReadonlyUrl();
        }
        return d.getUrl();
    }

    @Bean
    public SchemaProvider nl2sqlSchemaProvider(DataSource nl2sqlAdminDataSource, Nl2SqlProperties props) {
        return new SchemaProvider(nl2sqlAdminDataSource, props);
    }

    @Bean
    public NlToSqlService nlToSqlService(ChatModel chatModel,
                                         JdbcTemplate nl2sqlReadOnlyJdbc,
                                         SchemaProvider nl2sqlSchemaProvider,
                                         Nl2SqlProperties props,
                                         AuditLogger audit) {
        SqlGuard guard = new SqlGuard(props.getAllowTables(), props.getTenantScopedTables(),
                props.getMaxRows(), props.isEnforceTenantPredicate());
        SqlQueryTool tool = new SqlQueryTool(nl2sqlReadOnlyJdbc, guard, props.getMaxToolCalls());
        SqlAssistant assistant = AiServices.builder(SqlAssistant.class)
                .chatModel(chatModel)
                .tools(tool)
                .build();
        return new NlToSqlService(assistant, nl2sqlSchemaProvider, audit, props.isNumberGrounding());
    }

    private static HikariDataSource pool(String url, String user, String pass, boolean readOnly, String name) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(url);
        c.setUsername(user);
        c.setPassword(pass);
        c.setReadOnly(readOnly);          // 只读池再加一层 connection.setReadOnly（L1 之上的纵深）
        c.setMaximumPoolSize(readOnly ? 4 : 2);
        c.setPoolName(name);
        return new HikariDataSource(c);
    }
}
