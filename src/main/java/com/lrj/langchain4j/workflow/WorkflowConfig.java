package com.lrj.langchain4j.workflow;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

/**
 * Flowable 引擎装配。整体 {@code @ConditionalOnProperty(app.workflow.enabled)}，默认关 = 零开销、
 * 不影响现有启动（默认无主 SQL 数据源的前提保持不变）。
 *
 * <p><strong>为什么手动 buildProcessEngine 而不用 flowable starter</strong>：见 pom.xml 注释。
 * 一句话——starter 的自动装配只要在 classpath 就会触发并强依赖一个自动装配的主 DataSource，
 * 与本项目"默认无主 SQL 源 + DataSourceAutoConfiguration 已排除"冲突。这里用 flowable-spring
 * 核心，引擎只在开关打开时由本类显式构建。
 *
 * <p>三个落地决定（对应 docs/workflow-integration.md 的 3 个坑）：
 * <ul>
 *   <li><b>坑 1 无主 SQL 源</b>：这里手动建独立 Hikari DataSource（MySQL，驱动复用 mysql-connector-j），
 *       <em>不</em>注册成全局 {@code @Primary} DataSource，避免跟 NL2SQL 只读库互相污染。</li>
 *   <li><b>坑 2 async executor 的 ThreadLocal 真空</b>：{@code setAsyncExecutorActivate(false)} —— v1
 *       所有 ServiceTask 在触发线程（已过过滤器链、{@code TenantContext} 有值）同步执行。delegate
 *       内仍从流程变量防御性重设 {@code TenantContext}，将来切 async executor 时业务不用改。</li>
 *   <li><b>坑 3 租户隔离</b>：见 {@link WorkflowService} —— 用流程变量 {@code tenantId} 过滤待办，
 *       不依赖 Flowable 原生 start-tenant 查找（classpath 无租户部署 + start 带 tenant 需 fallback 配置，
 *       流程变量过滤更简单且同样严格）。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
@EnableConfigurationProperties(WorkflowProperties.class)
public class WorkflowConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkflowConfig.class);

    /** classpath 下的 BPMN，引擎启动时自动部署。 */
    private static final String PROCESS_RESOURCE = "processes/refund-approval.bpmn20.xml";

    /** 对应 BPMN 里 process 的 id（与 {@link WorkflowService} 保持一致）。 */
    private static final String PROCESS_KEY = "refundApproval";

    /** Flowable 引擎专用数据源（建 ACT_* 表）。独立命名，不抢全局主 DataSource 位。 */
    @Bean
    public DataSource workflowDataSource(WorkflowProperties props) {
        WorkflowProperties.Datasource d = props.getDatasource();
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(d.getUrl());
        c.setDriverClassName(d.getDriverClassName());
        c.setUsername(d.getUsername());
        c.setPassword(d.getPassword());
        c.setMaximumPoolSize(8);
        c.setPoolName("flowable");
        return new HikariDataSource(c);
    }

    @Bean
    public PlatformTransactionManager workflowTransactionManager(DataSource workflowDataSource) {
        return new DataSourceTransactionManager(workflowDataSource);
    }

    @Bean
    public ProcessEngine workflowProcessEngine(DataSource workflowDataSource,
                                               PlatformTransactionManager workflowTransactionManager,
                                               ApplicationContext applicationContext) {
        SpringProcessEngineConfiguration cfg = new SpringProcessEngineConfiguration();
        cfg.setDataSource(workflowDataSource);
        cfg.setTransactionManager(workflowTransactionManager);
        // 自动建 / 升级 ACT_* schema
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        // 坑 2：关掉 async executor —— ServiceTask 在触发线程同步跑，TenantContext 不丢
        cfg.setAsyncExecutorActivate(false);
        // #4 历史表无限增长：显式钉死 history level=audit（够留痕：实例/任务/变量历史），
        // 不用 full（连每次变量更新明细都记，灌爆 ACT_HI_DETAIL）。审计留痕另走 logs/audit.jsonl，
        // Flowable 历史只为流程态查询，配合 WorkflowHistoryCleaner 定期按保留期清理。
        cfg.setHistory("audit"); // Flowable history level：none|activity|audit|full|variable
        // 让 ${serviceTaskDelegates.assess(execution)} 这种表达式能解析到 Spring bean
        cfg.setApplicationContext(applicationContext);

        ProcessEngine engine = cfg.buildProcessEngine();

        // 显式部署 BPMN（比 setDeploymentResources 的自动部署可靠：手动 buildProcessEngine() 下
        // 自动部署不一定触发；显式部署还能立刻拿到 process definition 数做断言、parse 错误会大声抛）。
        // enableDuplicateFiltering：内容没变就不重复建版本（tenant-less，租户隔离走流程变量，见 WorkflowService）。
        engine.getRepositoryService().createDeployment()
                .name("refund-approval")
                .addClasspathResource(PROCESS_RESOURCE)
                .enableDuplicateFiltering()
                .deploy();
        long defCount = engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionKey(PROCESS_KEY).count();
        log.info("Flowable ProcessEngine 已启动（async executor 关 / 同步执行 / history=audit）；已部署 {}（refundApproval 版本数={}）",
                PROCESS_RESOURCE, defCount);
        logVersionTopology(engine);
        return engine;
    }

    /**
     * #6 流程定义版本化可见性：按定义版本打印在途实例分布。
     *
     * <p>Flowable 原生语义 = <b>每个实例终生跑自己启动时的定义版本</b>（"继续旧版"已是默认，无需额外代码）。
     * 改 {@code refund-approval.bpmn20.xml} 重部署只产生新版本，已挂起的旧实例仍按旧定义跑完——这是
     * 安全的，但若做了<b>结构性</b>改动（删节点/改网关），跨版本可能不兼容。本方法把"还有多少在途实例
     * 卡在非最新版本"打出来，运维改 BPMN 前据此决策：
     * <ul>
     *   <li>仅文案/描述微调 → 直接改、重部署（{@code enableDuplicateFiltering} 保证内容没变不重复建版本）；</li>
     *   <li>结构性改动且有在途旧实例 → 换 {@code process id}（新 key），新老流程并存、互不影响，旧实例自然跑干。</li>
     * </ul>
     */
    private void logVersionTopology(ProcessEngine engine) {
        try {
            List<ProcessDefinition> defs = engine.getRepositoryService().createProcessDefinitionQuery()
                    .processDefinitionKey(PROCESS_KEY)
                    .orderByProcessDefinitionVersion().asc()
                    .list();
            if (defs.isEmpty()) {
                return;
            }
            int latest = defs.get(defs.size() - 1).getVersion();
            for (ProcessDefinition d : defs) {
                long running = engine.getRuntimeService().createProcessInstanceQuery()
                        .processDefinitionId(d.getId()).count();
                if (running > 0 && d.getVersion() < latest) {
                    log.warn("refundApproval v{} 仍有 {} 个在途实例（最新为 v{}）——按旧定义跑完；做结构性改动请换 process key 避免不兼容（#6）",
                            d.getVersion(), running, latest);
                } else {
                    log.info("refundApproval v{}：在途实例={}", d.getVersion(), running);
                }
            }
        } catch (Exception e) {
            // 仅诊断日志，失败不影响启动
            log.warn("打印流程版本分布失败（忽略）：{}", e.toString());
        }
    }

    @Bean
    public RuntimeService workflowRuntimeService(ProcessEngine workflowProcessEngine) {
        return workflowProcessEngine.getRuntimeService();
    }

    @Bean
    public TaskService workflowTaskService(ProcessEngine workflowProcessEngine) {
        return workflowProcessEngine.getTaskService();
    }

    @Bean
    public HistoryService workflowHistoryService(ProcessEngine workflowProcessEngine) {
        return workflowProcessEngine.getHistoryService();
    }

    @Bean
    public RepositoryService workflowRepositoryService(ProcessEngine workflowProcessEngine) {
        return workflowProcessEngine.getRepositoryService();
    }
}
