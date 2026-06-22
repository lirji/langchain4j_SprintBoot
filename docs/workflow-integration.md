# 业务落地接入设计：渠道 / SSO / 工作流编排

这份文档记录"把项目落到实际客服 / 知识库场景"的接入设计与决策。
区别于 `docs/production-hardening.md`（已落地的业务平台基线 #1–#8）。

> **状态（2026-06-02 更新）**：**工作流编排（Milestone 1.A + 1.A.1 + 1.A.2 + 1.A.3）已落地并端到端跑通**
> —— `workflow/` 包 + `processes/refund-approval.bpmn20.xml` + 8 个 REST 端点全部就位，默认
> `app.workflow.enabled=false`。**上线硬化 gap 清单 #1–#10 全清**：#1 超时 / #2 幂等（M1.A.1）+ #3 事务补偿 /
> #4 历史表 / #5 大变量 / #6 版本化（M1.A.2）+ #7 并发审批 / #8 回推 outbox / #9 可观测性 / #10 PII 删除（M1.A.3）。
> **渠道接入（飞书，Milestone 1.B）已实施**：入站意图路由（退款/投诉→工作流，其余→对话）+ 验签解密 + 5s ack + 异步回推 +
> 审批卡片闭环，默认 `app.channel.feishu.enabled=false`，详见下「渠道（飞书）落地」。**SSO/OAuth** 仍暂缓（备查节）。

> **场景规划定位（2026-06-02）**：本项目下一阶段落地**两个并行业务场景** ——
> **#1 智能客服全闭环（本文）** + **#2 NL2SQL / ChatBI（`docs/nl2sql.md`）**。
> #2 更独立（纯 `@Tool` 扩展、无新有状态依赖），建议**先 #2 后 #1**；但两者解耦，顺序可调。
> 本文工作流（Milestone 1.A）→ 飞书渠道（Milestone 1.B）两段也可独立交付。
> **注意**：#1 的 Flowable 与 #2 的只读库都要引 `spring.datasource`，两特性同开时第二 DataSource
> 必须 `@Qualifier` 显式区分（建议主 DataSource = Flowable，NL2SQL 用命名 Bean）——详见 `docs/nl2sql.md` 坑 1。

> 触发背景：production-hardening 已经把 auth / 限流 / 配额 / 文档生命周期 / 审计 / 异步 / 推送
> 这套 SaaS 平台层做完了。要真正落到"客服 + 知识库"业务，还差三块对外接入：
> **① 工作流编排（人工审批 / 状态持久化）② SSO/OAuth ③ 渠道（企微/钉钉/飞书/Web/IVR）**。

---

## 阶段决策（已确认）

| 接入块 | 决策 | 理由 |
| --- | --- | --- |
| **工作流编排** | **路线 2：引入 Flowable（BPMN 引擎）** | 客服流程多变、要可视化编排 + 多级审批，BPMN 是主场；审批任务表/建模器开箱即用 |
| **SSO / OAuth** | **暂缓**，继续用现有 `X-Api-Key` | IdP 还没定；现有 `ApiKeyAuthFilter` + `TenantContext` 够用，OAuth 是平行加 `JwtAuthFilter`，随时能补 |
| **渠道** | 样板选 **飞书**（交互卡片最全，审批 UI 零额外前端） | 打通"异步 ack + 主动回推"范式后，企微/钉钉/Web/IVR 靠复制 |
| **实施顺序** | ✅ **工作流（M1.A）→ 飞书渠道（M1.B）** 均已落地 | 用户指定；按此顺序交付完成 |

### 三块怎么跟现有代码咬合（总图）

```
                    [ 渠道层 · 待建 ]
企业微信/钉钉/飞书/Web/IVR
   │ 各自的回调签名 + 5s ack + 异步回推
   ▼
ChannelAdapter ──► 归一成 (channelUserId, text) ──► 解析 tenantId/chatId
   ▼
┌─────────────────────────────────────────────┐
│ 过滤器链（已有, production-hardening #1）        │
│  TraceIdFilter                                │
│  ┌─ ApiKeyAuthFilter   (已有, X-Api-Key)       │
│  └─ JwtAuthFilter      [暂缓, OAuth/SSO]       │ ← 两条都喂同一个 TenantContext
│  RateLimitFilter / TokenBudgetGuardFilter(已有) │
└─────────────────────────────────────────────┘
   ▼
Controller ──► Assistant.chat (RAG + 记忆 + guardrail, 已有)
   │
   └─►(命中需人工/工单的意图) Flowable 工作流 [本阶段在做]
          BPMN: ...→ UserTask(人工审批) →...   (挂起等人, DB 持久化)
          人工审批后 resume → 完成 → 复用已有 SSE/Webhook / 渠道回推
```

共同支点：**`TenantContext`（强类型租户身份 ThreadLocal）+ 已有的异步/推送/审计基建**。

---

## 工作流编排（Flowable）详细设计

### 为什么是 Flowable 而不是扩现有 async 状态机

现有 `async` 包（production-hardening #7）是"**一次性后台任务**"：
`PENDING → RUNNING → SUCCEEDED|FAILED|CANCELLED`，`ConcurrentHashMap` 内存存，重启即丢。
它**不是工作流引擎**——没有"挂起等人"、没有持久化、没有多步编排。

客服典型长流程需要的恰恰是这些：
```
用户问 → bot 答 → 命中"退款/改单/投诉升级"意图
       → 抽工单 → 路由人工 → [挂起，等审批，可能几分钟到几天] → 人工通过/驳回
       → bot 把结果回推用户 → 关单
```
那个 `[挂起，等审批]` 期间服务会重启，内存状态机扛不住。Flowable 的 `UserTask` + 引擎表持久化天然解决。

### 这个代码库特有的 3 个坑（实施前必须处理）

**坑 1：项目当前没有主 SQL 数据源，但 Flowable 强依赖。**
现有持久化是 Redis（ChatMemory）+ 向量库 + `mysql-connector-j`（仅给 Doris 走 MySQL 协议）。
Flowable 引擎需要一个 JDBC `DataSource`，启动时自动建 ~25 张 `ACT_*` 表。处理：
- 引 `flowable-spring-boot-starter-process:7.1.0`（**7.x** 才兼容 Spring Boot 3.3.5 / Java 21；6.x 是 Spring Boot 2）。
  **版本已钉死 7.1.0**：7.x 系专为 Spring Boot 3 / Spring 6 / Java 17 而生；最新 7.2.0 也兼容 SB3，但
  已知 **7.2.0 不兼容 Spring Boot 4**，本项目在 3.3.5，选保守稳妥的 7.1.0。升级 SB 到 4.x 时再评估 Flowable 8
- 配 `spring.datasource.*`：**dev 用 H2 内存**（零运维），**prod 用 MySQL/PG**
- 这是新引入的**有状态依赖**，跟 production-hardening "内存起步"风格不同，需单独记一笔

**坑 2：LLM ServiceTask 必须避开 async executor 的 ThreadLocal 真空。**
工作流里"调 RAG 答问 / 抽工单"是 BPMN `ServiceTask`。Flowable 的 async executor 线程**不经过过滤器链**，
`TenantContext`（ThreadLocal）是空的——这跟 multi-agent worker fan-out 当初的坑一模一样
（production-hardening #1 用 `MdcCopyingTaskDecorator` 解决）。

> **v1 简化方案**：ServiceTask **不开 `flowable:async`**，让它们在**触发线程**上同步执行
> （用户请求线程 / 审批人请求线程都经过过滤器链，`TenantContext` 有值）。
> 即便如此，delegate 里仍**从流程变量 `tenantId` 防御性重设 `TenantContext`**（存旧值 → set → finally 还原），
> 将来真要切 async executor 时业务逻辑不用改，只需给 Flowable 的 async executor 也装一个
> 类似 `MdcCopyingTaskDecorator` 的上下文传播。

**坑 3：租户隔离用 Flowable 原生多租户，不要自己加 filter。**
Flowable 的 `ProcessInstance` / `Task` 自带 `tenantId` 字段。
- 启流程：`runtimeService.createProcessInstanceBuilder().tenantId(t).start()`
- 查待办：`taskService.createTaskQuery().taskTenantId(t).list()`
直接对齐现有 `TenantContext.tenantId()`，审批列表天然按租户隔离，与 #1 的隔离语义一致。

### 样板流程：退款审批（`refund-approval`）

BPMN 流程定义放 `src/main/resources/processes/refund-approval.bpmn20.xml`。

```
Start(入参: tenantId, userId, chatId, message)
  ▼
ServiceTask "assess"      ──► 调 Extractor.extractTicket(message)
  │                            写流程变量: priority, category, summary
  ▼
ExclusiveGateway          ──► needsApproval = priority ∈ {HIGH, CRITICAL}
  ├─ true  ─► UserTask "approveRefund"  (taskTenantId=tenant; 挂起等人)
  │              完成时带变量: approved(boolean), comment
  │              ▼
  │           Gateway approved?
  │              ├─ true  ─► ServiceTask "resolve"
  │              └─ false ─► ServiceTask "reject"
  └─ false ─────────────────► ServiceTask "resolve"
  ▼
ServiceTask "resolve"     ──► 调 Assistant.chat(chatId, ..., message) 生成答复
  │                            写流程变量: reply
ServiceTask "reject"      ──► reply = 驳回话术(含 comment)
  ▼
End
```

测试时**不依赖任何渠道**即可端到端跑通：

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `POST` | `/workflow/refund/start` | 启流程；自动分支立即返回 `{instanceId, status:COMPLETED, reply}`；需审批则 `{instanceId, status:WAITING_APPROVAL, taskId}`。body 可选 `dedupeId`（幂等）/`webhookUrl`（终态 outbox 回推） |
| `GET`  | `/workflow/tasks` | 本租户待审 UserTask 列表（按流程变量 `tenantId` 过滤），含 `assignee` |
| `POST` | `/workflow/tasks/{taskId}/claim` | 认领任务（设 assignee=当前用户）；已被他人领 → 409（M1.A.3 #7） |
| `POST` | `/workflow/tasks/{taskId}/unclaim` | 取消认领，放回待领池（M1.A.3 #7） |
| `POST` | `/workflow/tasks/{taskId}/complete` | body `{approved, comment}` → `taskService.complete(...)` → 同步跑 resolve/reject → 返回 `{reply}`；并发双重审批 → 409 |
| `GET`  | `/workflow/instances/{instanceId}` | 实例状态 + reply（完成后） |
| `DELETE` | `/workflow/data?chatId=` | PII 合规删除：清本租户该 chatId 的运行/历史实例 + reply + outbox（`SCOPE_approve`，M1.A.3 #10） |

> reply 暂存流程变量，由 status 端点取回。**接飞书渠道后**，改为 UserTask 推交互卡片 +
> 完成后主动回推用户（衔接渠道阶段）。

### 计划新增结构

```
workflow/
├── WorkflowConfig.java          Flowable 引擎配置（数据源、tenant、关闭无关特性）
├── ServiceTaskDelegates.java    BPMN ServiceTask → 调 Extractor/Assistant（重设 TenantContext）
├── WorkflowService.java         启流程 / 查待办 / 完成审批，封装 Flowable RuntimeService+TaskService
└── controller/WorkflowController.java   上表 4 个 REST 端点
src/main/resources/processes/refund-approval.bpmn20.xml
```

依赖现有：`Extractor`（抽工单）、`Assistant`（生成答复）、`TenantContext`（租户）、`AuditLogger`（审计）。

### 审计挂点（复用 production-hardening #6）

`AuditEventType` 新增：

| 事件 | 触发点 |
| --- | --- |
| `workflow.started` | `WorkflowService.start` |
| `approval.requested` | 流程进入 UserTask（挂起待审） |
| `approval.granted` / `approval.rejected` | `WorkflowService.complete` 按 `approved` 分流 |
| `workflow.completed` | 流程到达 End |

合规场景审批留痕是硬需求，已有审计基建直接复用（专用 `AUDIT` logger → `logs/audit.jsonl`）。

### 安全 / RBAC

- 审批端点用现有 `@PreAuthorize` + scope 机制（`ApiKeyAuthFilter` 把 scope 加 `SCOPE_` 前缀）。
  计划新增 scope `approve`：`GET/POST /workflow/tasks*` 要求 `@PreAuthorize("hasAuthority('SCOPE_approve')")`。
- `/workflow/refund/start` 普通用户可发起（`chat` scope 即可），但 `complete` 必须 `approve` scope。

### 实施纪要（已落地，与设计的 3 处偏差）

- [x] **Flowable 依赖**：改用 **`org.flowable:flowable-spring:7.1.0`**（core + Spring 集成），**不是**原计划的
  `flowable-spring-boot-starter-process`。原因：starter 的自动装配只要在 classpath 就会触发并强依赖一个
  自动装配的主 DataSource，而本项目 `DataSourceAutoConfiguration` 已在主 App 排除（默认无主 SQL 源）→
  starter 会让默认启动直接报错。`flowable-spring` 无 spring-boot autoconfig，引擎由 `WorkflowConfig`
  在 `@ConditionalOnProperty` 下手动 `buildProcessEngine()`，关闭时零开销 —— 与 `LlmConfig` 绕开 ollama
  starter、`Nl2SqlConfig` 手动建 DataSource 同一套路。
- [x] **数据源用 MySQL（不是 H2）**：`app.workflow.datasource.*`，默认本机
  `localhost:3306/flowable`（`createDatabaseIfNotExist=true` 首次自动建库 + `nullCatalogMeansCurrent=true`
  避开 Flowable+MySQL8 跨库扫表的已知坑）。驱动复用既有 `mysql-connector-j`。它是**独立 Hikari 池**、
  不注册成全局 `@Primary` DataSource，与 NL2SQL 只读库互不污染（故无需 `@Qualifier` 之争）。
- [x] **租户隔离用流程变量，不用 Flowable 原生 start-tenant**：classpath 是 tenant-less 部署，
  带 tenant 启动需 fallback 配置；改为发起时把 `tenantId` 写成流程变量，待办/完成/查实例都按
  `processVariableValueEquals("tenantId", 当前租户)` 过滤，更简单且同样严格（坑 3 的等价实现）。
- [x] `@ConditionalOnProperty(app.workflow.enabled)` 包住整套（Config / Service / Delegates / Controller），默认关。
- [x] BPMN：**classpath 自动部署**（`SpringProcessEngineConfiguration.setDeploymentResources`）。
- [x] **坑 2**：`setAsyncExecutorActivate(false)`，ServiceTask 在触发线程同步执行；delegate 内仍从流程变量
  防御性重设 `TenantContext`（`ServiceTaskDelegates.withTenant`）。
- [x] 审批留痕：`AuditEventType` 已加 `workflow.started/approval.requested/granted/rejected/workflow.completed`。
- [x] RBAC：新增 `approve` scope；`/workflow/tasks*` 要求 `SCOPE_approve`，`application.yml` seed 了
  `dev-key-tenantA-approver`。

落地结构（实际）：

```
workflow/
├── WorkflowConfig.java          手动建 DataSource + ProcessEngine（@ConditionalOnProperty）；history=audit + 版本分布日志
├── WorkflowProperties.java      app.workflow.* 绑定（超时 / 幂等 / LLM 重试 / 历史保留）
├── ServiceTaskDelegates.java    BPMN ServiceTask（assess/resolve/reject）+ TenantContext 重设 + withRetry 降级补偿（#3）
├── WorkflowService.java         启流程 / 查待办 / 完成审批 / 查实例（按流程变量隔离租户）；reply 走 ReplyStore
├── ApprovalTimeoutSweeper.java  @Scheduled 审批超时自动驳回（M1.A.1 #1）
├── WorkflowHistoryCleaner.java  @Scheduled 历史实例 + WF_REPLY 行按保留期清理（M1.A.2 #4）
├── WorkflowReplyStore.java      reply 持久化接口（出流程变量，#5）
├── JdbcWorkflowReplyStore.java  WF_REPLY 表实现（workflowDataSource，写 join 同事务）
├── WorkflowMetrics.java         Micrometer 工作流指标（挂起 gauge / 审批耗时 / 终态 counter，M1.A.3 #9）
├── WorkflowOutbox.java          WF_OUTBOX 表 + 退避调度纯函数（终态回推 outbox，M1.A.3 #8）
└── WorkflowOutboxDispatcher.java @Scheduled 重投状态机（DELIVERED/DEAD DLQ，M1.A.3 #8）
controller/WorkflowController.java   8 个 REST 端点（含 claim/unclaim/data 删除，M1.A.3 #7/#10）
src/main/resources/processes/refund-approval.bpmn20.xml
```

### 端到端验证（2026-06-02，本地实跑）

环境：本机 MySQL（自动建 `flowable` 库 + 25 张 `ACT_*` 表）+ Ollama `qwen3:14b`（chat）。
启动：`APP_WORKFLOW_ENABLED=true WORKFLOW_DB_PASSWORD=root APP_LLM_OLLAMA_MODEL_NAME=qwen3:14b mvn spring-boot:run`。
三条分支 + RBAC + 租户隔离 + 审计全部跑通：

| 场景 | 请求 | 结果 |
| --- | --- | --- |
| **低风险自动受理** | start（"杯子不喜欢颜色想退，不急"） | `priority=LOW` → 直接 `COMPLETED`，reply 为 LLM 生成的受理话术 |
| **高风险→批准** | start（"#88231 付款 5 天不发货，否则投诉"）→ approver complete `approved:true` | start 返回 `WAITING_APPROVAL`+taskId；approve 后 `COMPLETED`，reply 为 LLM 答复 |
| **高风险→驳回** | start（同上紧急框架）→ approver complete `approved:false` | `COMPLETED`，reply 为驳回话术（含审批意见，来自 `rejectionMessage()`） |
| **RBAC** | admin（无 `approve`）访问 `/workflow/tasks` | 403；approver（有 `approve`）→ 200 |
| **租户隔离** | tenantB 试图 complete tenantA 的任务 | 403（缺 scope）；tenantA 的任务仅 tenantA approver 在 `/workflow/tasks` 可见（按流程变量 `tenantId` 过滤） |
| **审计** | `logs/audit.jsonl` | `workflow.started / approval.requested / approval.granted|rejected / workflow.completed` 全部落盘，tenantId 正确归属 |

> 验证中修了一处：`SpringProcessEngineConfiguration.setDeploymentResources()` 在手动 `buildProcessEngine()`
> 下未触发自动部署（启流程报 `No process definition found for key 'refundApproval'`），改为 `WorkflowConfig`
> 里显式 `repositoryService.createDeployment().addClasspathResource(...).enableDuplicateFiltering().deploy()`，
> 并打日志确认 `refundApproval 版本数=1`。

> 注：用例 `priority` 由模型判定，措辞不够"紧急/有业务影响"时可能被判为非 HIGH 而走自动受理——
> 要稳定触发审批分支，描述需体现 deadline / 投诉升级 / 阻断性影响（见 `Extractor` 的 priority rubric）。

---

## 上生产前待补的工作流问题（gap 清单）

> Milestone 1.A 已端到端跑通三分支 + RBAC + 租户隔离 + 审计，但那是 **curl 手测的 happy path**。
> 下面是审批类长流程**上线后真正咬人**、当前实现尚未覆盖的问题，按"会不会真出事故"分三档。
> **接飞书渠道前，一档必须做完**。

### 决策表（触发信号 → 该做什么）

| # | 问题 | 触发信号（什么时候会炸） | 该做什么 | 档 |
| --- | --- | --- | --- | --- |
| 1 | ~~**审批超时 / SLA / 升级**~~ ✅ | UserTask 挂起后审批人请假/漏看 → 流程**永久挂起**，用户永远收不到回复 | ✅ **M1.A.1（2026-06-02）**：`ApprovalTimeoutSweeper` `@Scheduled` 扫挂起超 `app.workflow.approval-timeout`（默认 PT24H）的 UserTask → `WorkflowService.expireTask` 自动驳回 + `approval.timeout` 审计。详见下「M1.A.1 落地」 | 🔴 一档 |
| 2 | ~~**幂等 / 重复启动**~~ ✅ | 飞书 ~5s ack 超时重推同一条消息（LLM 常超 5s）→ 一个诉求起 N 个流程 + N 个审批任务 | ✅ **M1.A.1（2026-06-02）**：`start(chatId, message, dedupeId)` → Flowable `businessKey=tenant:chatId:dedupeId` + start 前查重复用既有实例。无 dedupeId 走随机 UUID（不去重） | 🔴 一档 |
| 3 | ~~**`complete` 同步跑 LLM 的事务边界 + 失败补偿**~~ ✅ | 审批人点"通过" → HTTP 卡几十秒等 LLM；或 `complete()` 已提交后 LLM 挂 → 任务完成但 `reply` 没生成（状态不一致） | ✅ **M1.A.2（2026-06-02）**：`ServiceTaskDelegates.withRetry` 给 assess/resolve LLM 调用加有界重试 + 降级补偿——**绝不向 Flowable 抛异常**，重试耗尽则写降级兜底答复，事务照常提交。事务边界 = 「人工决定 + 一定有终态 reply」原子提交，LLM 是事务内 best-effort。详见下「M1.A.2 落地」 | 🔴 一档 |
| 4 | ~~**历史表无限增长**~~ ✅ | `ACT_HI_*` 默认无 TTL，跑几个月几千万行 → 查询/备份双双拖垮（Flowable 运维头号问题） | ✅ **M1.A.2**：`WorkflowConfig` 显式 `history=audit`（不用 `full`）+ `WorkflowHistoryCleaner` `@Scheduled` 删超 `app.workflow.history-retention`（默 P30D）的已结束历史实例 + `WF_REPLY` 行 | 🟡 二档 |
| 5 | ~~**大文本进流程变量**~~ ✅ | `reply`（LLM 长答复）/`summary` 存流程变量 → 灌 `ACT_RU_VARIABLE`/`ACT_HI_VARINST`，放大 #4 | ✅ **M1.A.2**：`reply` 挪出流程变量到业务表 `WF_REPLY`（`WorkflowReplyStore`，建在 workflow 数据源、写 join 同事务 → 原子 + 持久），status 端点从表取回。priority/category/summary 这种短字段仍留流程变量 | 🟡 二档 |
| 6 | ~~**流程定义版本化 / in-flight 实例**~~ ✅ | 改 `refund-approval.bpmn20.xml` 重部署 → 新实例走新版，**已挂起的旧实例仍按旧定义跑**；改结构时跨版本不兼容 | ✅ **M1.A.2**：续旧版是 Flowable 原生默认（每实例终生跑自己启动时的版本）；`WorkflowConfig.logVersionTopology` 启动时打印各版本在途实例数（迁移可见性）；策略文档化——微调直接重部署，结构性改动且有在途旧实例 → 换 `process id`（新 key） | 🟡 二档 |
| 7 | ~~**任务分配粒度 + 并发双重审批**~~ ✅ | 现在 `taskTenantId` 过滤 = 同租户任意 approver 审任意任务；两人同时点同一 task → 第二次 `complete()` 抛 `FlowableObjectNotFoundException`（500） | ✅ **M1.A.3（2026-06-02）**：`claim`/`unclaim` 端点（设 assignee，已被他人领 → 409）；`complete`/`expireTask` 把竞态 `FlowableObjectNotFoundException` 翻成友好 **409** 而非 500；`TaskView` 加 `assignee` | 🟢 三档 |
| 8 | ~~**回推用户"最后一公里"可靠性**~~ ✅ | 流程 `COMPLETED` 但回推（SSE/Webhook/飞书）失败 → 系统以为办完了，用户在干等 | ✅ **M1.A.3**：持久化 **outbox**（`WF_OUTBOX` 表）+ `WorkflowOutboxDispatcher` `@Scheduled` 重投（指数退避，4xx/超阈 → DEAD DLQ）。补 `WebhookDispatcher` 内存重试"进程一挂就丢"的缺口。start 传 `webhookUrl` 则终态入队，复用 `WebhookSigner` | 🟢 三档 |
| 9 | ~~**工作流可观测性**~~ ✅ | observability 只覆盖 LLM 调用，**无工作流维度**：挂起数 / 平均审批时长 / 超时率 / 分支占比 | ✅ **M1.A.3**：`WorkflowMetrics` 接 Micrometer——`workflow.tasks.pending`(gauge) / `workflow.approval.duration`(timer) / `workflow.completed`(counter,tag=outcome) / `workflow.started`(tag=priority) / `workflow.approval.timeout`(counter)，同走 `/actuator/prometheus` | 🟢 三档 |
| 10 | ~~**PII 合规删除**~~ ✅ | `message`/`summary` 含用户 PII 进了 Flowable 持久化表；个保法"删除我的数据"请求要能定位清除 | ✅ **M1.A.3**：`WorkflowService.purge(chatId)` 删运行/历史实例（`ACT_*`）+ `WF_REPLY` + `WF_OUTBOX`；`DELETE /workflow/data?chatId=`（`SCOPE_approve`）；审计 `workflow.data_purged` | 🟢 三档 |

### 优先级建议

- **接飞书渠道前必须做**：~~#1（超时升级）、#2（幂等）~~ ✅ **已落地（M1.A.1，2026-06-02）**，见下「M1.A.1 落地」。
- **紧随**：~~#3（事务/补偿）~~ ✅ **已落地（M1.A.2，2026-06-02）**，见下「M1.A.2 落地」。
- **上量前的功课**：~~#4–#5（历史表 / 大变量）~~ ✅ **已随 M1.A.2 一起落地**（#5 的 `WF_REPLY` 业务表同时服务 #3 的持久化补偿、#4 的清理；三者一处咬合）。
- **其余按需**：~~#7–#10~~ ✅ **已落地（M1.A.3，2026-06-02）**——并发审批/claim、回推 outbox、工作流 metrics、PII 删除全部就位，见下「M1.A.3 落地」。至此 gap 清单 #1–#10 全清。

> 这些问题的共性：**curl happy path 测不出来**，全是"挂起期间出意外 / 渠道重试 / 跑久了 / 多人并发"才暴露。
> 与 `docs/roadmap.md` 的"触发信号 → 该做什么"决策表风格一致，可按 ROI 并入总览。

### M1.A.1 落地（2026-06-02）：#1 超时 + #2 幂等

接飞书渠道前的 🔴 一档前两项落地。**两个关键设计决定**：

1. **超时走 `@Scheduled` 扫描，不走 BPMN boundary timer**。Flowable timer 必须
   `asyncExecutorActivate=true` 才触发（已对 flowable-7.1.0 文档确认），而 `WorkflowConfig` 刻意关 async executor
   规避坑 2（async 线程 ThreadLocal 真空）。走调度线程则**保持 async executor 关、零线程模型改动、现有三分支零回归**，
   符合 v1 简化哲学。代价：轮询粒度 + 超时逻辑在 Java 不在 BPMN。原先设想的「重开 async executor + Flowable 线程
   上下文传播」整块被取消。
2. **超时去向 = 自动驳回 + 审计**（不是升级上级——项目暂无审批层级概念，那属 #7）。保证用户总能收到终态回复。

| 项 | 关键文件 / 改动 |
| --- | --- |
| 超时扫描 | `workflow/ApprovalTimeoutSweeper.java`（新）：`@Scheduled(fixedDelayString=${app.workflow.timeout-sweep-interval-ms:60000})` 扫 `taskCreatedBefore(now - approvalTimeout).active()`，逐任务调 `expireTask`，单条失败不阻断 |
| 超时驳回 | `WorkflowService.expireTask(taskId)`：`active().singleResult()==null` 幂等跳过（与人工 complete 竞态收口）→ 从流程变量重建 `TenantContext` → 走既有 reject 路径（`approved=false`）→ 审计 `approval.timeout` + `workflow.completed(timeout)` |
| 幂等 | `WorkflowService.start(chatId, message, dedupeId)` + `buildBusinessKey(...)`：dedupeId 非空用 `tenant:chatId:dedupeId` 查重复用既有实例；空则随机 UUID 不去重。`StartResult` 加 `deduplicated` 字段。`WorkflowController` body 加可选 `dedupeId` |
| 日志串联 | sweep 跑在调度线程（不过 `TraceIdFilter`/`ApiKeyAuthFilter`），故 `expireTask` 手动铺 MDC `traceId`/`tenantId`/`userId`（finally 还原）。`traceId` 优先取流程变量 `startTraceId`（`start` 时存的请求 traceId）→ `grep <traceId>` 串起「start」与「24h 后超时驳回」 |
| 配置 | `app.workflow.approval-timeout`（默认 `PT24H`）+ `app.workflow.timeout-sweep-interval-ms`（默认 60000）；`AuditEventType.APPROVAL_TIMEOUT("approval.timeout")` |
| 单测 | `WorkflowServiceTest`（buildBusinessKey 3 case）+ `ApprovalTimeoutSweeperTest`（cutoff 2 case），纯逻辑、不连 Flowable |

**残留 / 升级点**：start 查-建非原子，两并发同 dedupeId 仍可能都漏检→都建（v1 接受，渠道重推秒级近似串行）；
强幂等升级 = Redis `SETNX`（项目已用 `RedisChatMemoryStore`）或 dedup 表唯一索引。

**本地验证**（需 MySQL + Ollama，把超时压到 `PT20S`、sweep `5000ms`）：
```bash
APP_WORKFLOW_ENABLED=true WORKFLOW_DB_PASSWORD=root APP_LLM_OLLAMA_MODEL_NAME=qwen3:14b \
APP_WORKFLOW_APPROVAL_TIMEOUT=PT20S APP_WORKFLOW_TIMEOUT_SWEEP_INTERVAL_MS=5000 mvn spring-boot:run
```
1. 高优先级 start → 不 complete → 等 ~25s → `GET /workflow/instances/{id}` 为 `COMPLETED`，reply 为超时驳回话术；`logs/audit.jsonl` 有 `approval.timeout` + `workflow.completed(timeout)`，tenantId 归属正确。
2. 同 `dedupeId` 连发两次 start → 第二次返回同一 `instanceId` + `deduplicated:true`；`/workflow/tasks` 只见 1 个任务。

### M1.A.2 落地（2026-06-02）：#3 事务边界/补偿 + #4 历史表 + #5 大变量 + #6 版本化

接飞书渠道前的 🔴 一档最后一项（#3）+ 顺带把三件 🟡 二档（#4/#5/#6）一起收口——因为它们在 `WF_REPLY`
业务表这一点上咬合：reply 出流程变量（#5）既减小历史表膨胀（#4），又给 #3 的失败补偿提供持久落点。

**核心问题（#3）的精确定位**：`complete()` 调 `taskService.complete()` 时，async executor 关 → 下游
`resolve` ServiceTask 的 **LLM 调用在同一个 Flowable 事务内同步执行**。若 LLM 直接抛异常，整个事务回滚 →
**已记录的人工审批决定一并丢失**、任务退回 active、审批人吃 500、被迫重新审批。

**三个关键设计决定**：

1. **降级补偿而非异步化**。原 gap 设想"把 resolve 改异步 + 回推"，但那要重开 async executor（撞坑 2 的
   ThreadLocal 真空）。改走**事务内有界重试 + 降级兜底**：`ServiceTaskDelegates.withRetry` 最多试
   `app.workflow.llm-max-attempts` 次（默 2），耗尽则**返回兜底值、绝不抛异常**——于是事务边界变成
   「人工决定 + 一定有终态 reply」原子提交，LLM 是事务内 best-effort，失败降级不中止。保持 async executor 关、
   现有三分支零回归。代价：`complete()` 仍同步等 LLM（延迟未消除，但一致性 + "人工决定永不丢失"已保证）；
   彻底去延迟仍需异步化，留待渠道阶段（那时回推范式天然异步）。
2. **assess 降级 = 强制 HIGH 转人工**（不是默认 LOW）。抽工单失败时风险未知，宁可多一道人工审，
   绝不默认放过潜在高风险退款。`degradedTicket` 写 `priority=HIGH` + 原始消息进 summary。
3. **reply 落业务表 `WF_REPLY`（#5）而非内存**。`WorkflowReplyStore`（接口）+ `JdbcWorkflowReplyStore`
   建在 Flowable 同一个 `workflowDataSource` 上（启动自动 DDL）。写 reply 经 `JdbcTemplate` 复用 Spring
   绑定到该数据源的**事务连接**（Flowable 由同数据源的 `workflowTransactionManager` 驱动）→ 与流程推进
   **同事务原子提交、重启不丢**（保住引入 Flowable 的初衷，不像内存 store 重启即丢）。priority/category/summary
   这种短字段仍留流程变量。

| 项 | 关键文件 / 改动 |
| --- | --- |
| #3 重试/补偿 | `ServiceTaskDelegates.withRetry`（纯函数，单测覆盖"失败 N 次→兜底""先败后成"）；`degradedTicket`/`degradedResolveReply` 兜底；降级时审计 `reply.degraded`；配置 `app.workflow.llm-max-attempts`/`llm-retry-backoff-ms` |
| #5 reply 出流程变量 | `WorkflowReplyStore` + `JdbcWorkflowReplyStore`（`WF_REPLY` 表自动 DDL，upsert）；`ServiceTaskDelegates` 写 store、`WorkflowService` 各处 reply 读 `replyStore.find(instanceId)` 取代流程变量 |
| #4 历史表 | `WorkflowConfig.setHistory("audit")`（不用 full）；`WorkflowHistoryCleaner` `@Scheduled` 删超 `history-retention`（默 P30D）已结束实例 + `WF_REPLY` 行，逐条 try/catch；审计 `workflow.history_pruned` |
| #6 版本化 | `WorkflowConfig.logVersionTopology` 启动打印各定义版本在途实例数（旧版有在途则 WARN）；续旧版 = Flowable 原生默认；策略：微调直接重部署，结构性改动换 `process id` |
| 审计枚举 | `AuditEventType` 加 `REPLY_DEGRADED("reply.degraded")` / `WORKFLOW_HISTORY_PRUNED("workflow.history_pruned")` |
| 单测 | `ServiceTaskDelegatesTest`（+7 case：withRetry 4 + 兜底 3）、`WorkflowHistoryCleanerTest`（cutoff 2 case），纯逻辑、不连 Flowable/DB |

**残留 / 升级点**：
- #3 的延迟未消除（`complete()` 仍同步等 LLM）——彻底解决要异步化 + 回推，与渠道阶段合并做更顺。
- `WF_REPLY` 与 Flowable 表同库，PII 删除（#10）时两边一起清。
- 历史清理删掉的 COMPLETED 实例之后 `getInstance` 会 404（保留期外，reply 早已投递，可接受）。

**本地验证**（需 MySQL + Ollama）：
```bash
APP_WORKFLOW_ENABLED=true WORKFLOW_DB_PASSWORD=root APP_LLM_OLLAMA_MODEL_NAME=qwen3:14b mvn spring-boot:run
```
1. 正常 start/complete → `GET /workflow/instances/{id}` 的 reply 来自 `WF_REPLY` 表（`SELECT * FROM WF_REPLY` 可见行，`ACT_HI_VARINST` 里不再有 reply 变量）。
2. 模拟 LLM 故障（停 Ollama 后 complete 一个高优先级实例）→ 流程仍 `COMPLETED`、reply 为降级话术、任务**不**退回 active；`logs/audit.jsonl` 有 `reply.degraded`。
3. 启动日志可见 `refundApproval v1：在途实例=N`（#6 版本分布）。

### M1.A.3 落地（2026-06-02）：#7 并发审批 + #8 回推可靠性 + #9 可观测性 + #10 PII 删除

gap 清单最后四项（🟢 三档）一并收口，至此 #1–#10 全清。各自自包含、互不耦合：

**#7 任务分配粒度 + 并发双重审批**
- `claim`/`unclaim` 端点：`taskService.claim(taskId, userId)` 设 assignee；已被他人认领 → **409**（`claim` 内先查 assignee 自己判，不依赖特定 Flowable 异常类，版本鲁棒）。
- `complete`/`expireTask` 把"预检到 complete 之间被另一审批人/超时 sweeper 抢先处理"的竞态——`taskService.complete` 抛的 `FlowableObjectNotFoundException`——**catch 成 409**（友好冲突）而非裸 500。`expireTask` 的竞态则幂等跳过。
- `TaskView` 加 `assignee` 字段，待办列表能看出谁在审。
- **没引 candidateGroup**：项目暂无审批组概念，assignee + 同租户可见已够；要分组路由再加 BPMN `candidateGroups`。

**#8 回推"最后一公里"可靠性（持久化 outbox + DLQ）**
- 痛点：现有 `WebhookDispatcher` 是内存 `Thread.sleep` 重试，**进程一挂、重试中的投递就永久丢失**。
- `WF_OUTBOX` 表（建在 workflow 数据源，自动 DDL）+ `WorkflowOutboxDispatcher` `@Scheduled` 重投状态机：
  `PENDING`→2xx `DELIVERED` / 4xx 直接 `DEAD` / 5xx·网络错误指数退避重试，累计到 `max-attempts` 仍败 → `DEAD`（DLQ，人工捞）。重启后从库里接着投。
- `start` body 加可选 `webhookUrl`：传了才在**终态**（auto-complete / 人工 complete / 超时驳回）入队；不传则维持轮询（行为同旧版）。签名/超时复用 `app.async.webhook.*` 的 `WebhookSigner`/`WebhookProperties`，零新密钥。
- 退避决策 `WorkflowOutbox.schedule` 抽纯函数（4 case 单测：base / 指数 / 达阈 DEAD / 超阈 DEAD）。
- **target 现指 webhook，将来直接指飞书回调 URL**——渠道阶段零改动复用。

**#9 工作流可观测性（Micrometer）**
- `WorkflowMetrics`：`workflow.tasks.pending`(gauge，scrape 时查库) / `workflow.approval.duration`(timer，UserTask 创建→完成) / `workflow.completed`(counter, tag=outcome∈auto|granted|rejected|timeout) / `workflow.started`(tag=priority) / `workflow.approval.timeout`(counter)。
- 与 LLM 指标同走 `/actuator/prometheus`；`WorkflowService` 在各生命周期点打点。Grafana 可加"挂起 gauge / 审批耗时分位 / 超时率 / 分支占比"面板。

**#10 PII 合规删除**
- `WorkflowService.purge(chatId)`：按流程变量 `tenantId`+`chatId` 定位，删运行中实例（`deleteProcessInstance`）+ 历史实例（`deleteHistoricProcessInstance`，带走 `ACT_HI_*`）+ `WF_REPLY` + `WF_OUTBOX` 行。跨租户删不到。
- `DELETE /workflow/data?chatId=`（`SCOPE_approve`，破坏性操作）；审计 `workflow.data_purged`。与 PII guardrail + 文档生命周期同源（都按租户身份定位删除）。

| 项 | 关键文件 / 改动 |
| --- | --- |
| #7 | `WorkflowService.claim/unclaim` + `activeTenantTask` 抽取 + `complete`/`expireTask` catch `FlowableObjectNotFoundException`→409；`TaskView` 加 `assignee`；`WorkflowController` 加 claim/unclaim 端点 |
| #8 | `WorkflowOutbox`（`WF_OUTBOX` 表 + `schedule` 纯函数）+ `WorkflowOutboxDispatcher`（@Scheduled 重投）；`start` 加 `webhookUrl` 参数 + `enqueuePush`；`WorkflowProperties.Outbox` 配置；审计 `workflow.push_delivered/failed/dead` |
| #9 | `WorkflowMetrics`（gauge/timer/counter）；`WorkflowService` 注入并打点 |
| #10 | `WorkflowService.purge` + `DELETE /workflow/data`；审计 `workflow.data_purged` |
| 单测 | `WorkflowOutboxTest`（schedule 4 case）。#7/#9/#10 是 Flowable/DB/HTTP 交互，纯逻辑面薄，靠下方本地验证 |

**残留 / 升级点**：
- #7 未做 candidateGroup 分组路由（assignee 够用）；并发 claim 的查-判-claim 非原子，极端并发仍可能两人都过预检，但 `taskService.claim` 第二个会失败兜底 409。
- #8 outbox 单实例调度（多 pod 需给重投加行锁 / `SELECT ... FOR UPDATE SKIP LOCKED` 防多节点重复投）；payload 目前只含 reply，飞书要的卡片结构渠道阶段再加。
- #9 pending gauge 每次 scrape 查库，超大量级可改为事件增量维护。

**本地验证**（需 MySQL + Ollama）：
1. **并发**：两个 approver key 同 `taskId` 并发 `complete` → 一个 200、一个 **409**（不再 500）。`claim` 后另一人 `claim` → 409。
2. **outbox**：`start` 带 `webhookUrl`（指向本地 `nc -l` 或会 5xx 的端点）→ 高优先级 complete 后查 `SELECT * FROM WF_OUTBOX`：成功 `DELIVERED`，故意 5xx 看到 `ATTEMPTS` 递增、`NEXT_ATTEMPT_AT` 指数后移，最终 `DEAD`；`logs/audit.jsonl` 有 `workflow.push_*`。
3. **metrics**：`curl /actuator/prometheus | grep workflow_` 看到 `workflow_tasks_pending` / `workflow_approval_duration_*` / `workflow_completed_total{outcome=...}`。
4. **PII**：`DELETE /workflow/data?chatId=u1`（approver key）→ 返回 `purgedInstances`，之后 `WF_REPLY`/`WF_OUTBOX`/`ACT_HI_*` 中该 chatId 数据清空；`logs/audit.jsonl` 有 `workflow.data_purged`。

---

## 渠道（飞书）落地（Milestone 1.B 已实施，默认关）

样板选飞书：交互卡片最全，审批按钮直接做工作流 UI，零额外前端。`app.channel.feishu.enabled=true` 开启
（默认关，零开销）。**入站意图路由（用户选定）**：退款/投诉 → refund 工作流，其余 → Assistant 对话。

落地结构 `channel/feishu/` + `controller/channel/`：
- `FeishuController`（`POST /channel/feishu/event`）— 回调入口：URL 验证握手回 `challenge` + 消息事件 + 卡片回调；`SecurityConfig` 放行 `/channel/feishu/**`（飞书自带验签，不带 X-Api-Key）
- `FeishuCrypto` — `encrypt` 字段 AES-256-CBC 解密（`key=SHA256(encryptKey)`，IV 前置）+ `X-Lark-Signature` 验签（`hex(SHA256(ts+nonce+encryptKey+body))`，常量时间比较）。**纯函数，单测覆盖**（round-trip 解密 + 验签接受/拒篡改）
- `FeishuIntent` — 退款/投诉关键词分类（纯函数，单测）；要更细换 `ai/routing` 的 LLM 分类器
- `FeishuClient` — 出站 `im/v1/messages`（文本/卡片）+ `tenant_access_token` 缓存刷新（`AtomicReference`，提前 5 分钟过期）
- `FeishuChannelService` — 编排：归一 `(tenant, openId, text)` → 意图分流 → `@Async("feishuExecutor")` 处理 → 5s ack；含审批卡片构造 + 卡片回调 `complete`
- `FeishuReplyListener` — `@EventListener(WorkflowTerminalEvent)` `@Async`，`chatId` 以 `feishu:` 前缀则回推用户（**这就是"复用 #7/#8 事件机制，仅多一个监听器"**）
- `FeishuConfig` — `feishuExecutor` 线程池（复用 `MdcCopyingTaskDecorator` 透传 traceId）

4 个关键点（落地状态）：
1. **入站验签解密** ✅：`encrypt` 字段先 AES 解密；配了 `encrypt-key` 则校验 `X-Lark-Signature`；payload token 比对 `verification-token`；`url_verification` 原样回 `challenge`。
2. **`tenant_key` → `tenantId`** ⚠️ v1 单租户：所有入站归到 `app.channel.feishu.tenant`（默认 `tenantA`，对齐 seed 审批人 key）；`chatId = "feishu:" + open_id` → 复用 `chat:mem:tenantA:feishu:xxx` 多轮记忆。多租户 `tenant_key→tenantId` 映射留后续。
3. **异步 ack + 主动回推** ✅：收消息 → `@Async("feishuExecutor")` 处理 → controller 立刻 200（满足 ~5s）。CHAT 路径直接回推；WORKFLOW 低风险由 `WorkflowTerminalEvent` → `FeishuReplyListener` 回推（**不在 service 里重复推**）；高风险先回推"已转人工"ack，终态结果再经事件回推。
4. **审批卡片闭环**（Flowable ↔ 飞书）⚠️ 部分：配 `approver-chat-id` 则高风险时推带"通过/驳回"按钮的交互卡片到审批群，按钮 value 带 `{taskId, approved, tenant}`，回调 `FeishuController` → `FeishuChannelService.handleCardAction` → `WorkflowService.complete` → 终态回推用户。**飞书卡片回调 schema 随版本有差异，`handleCardAction` 按 `event.action.value` 防御性解析，接入时需对照实际卡片核对路径**；审批人也可继续走 REST `/workflow/tasks*`。

**无法本地纯测的部分**（需真飞书应用 + 公网回调）：出站 `FeishuClient`（token/发消息）、controller 端到端、卡片回调 schema。
crypto/intent 已纯逻辑单测；其余靠下方手测清单对真应用验证。

**本地/联调验证清单**：
1. 开发者后台配事件订阅 URL `https://<公网>/channel/feishu/event` → 看 `url_verification` 握手返回 `challenge`（日志 + 后台变绿）。
2. 给机器人发"你们几点上班" → CHAT 路径 → 收到 Assistant 回复。
3. 发"我要退款 订单一直不发货 很急" → WORKFLOW：高风险 → 收到"已转人工"ack + 审批群收到卡片；点"通过" → 收到最终答复。低风险（"颜色不喜欢想退 不急"）→ 直接收到受理答复。
4. `grep feishu logs/*.log` 看 traceId 串起「入站 → 路由 → 回推」。

渠道差异速查（飞书已落地，其余靠复制本范式）：

| 渠道 | 入站 | 出站 | 特殊点 |
| --- | --- | --- | --- |
| 飞书 ✅ | 事件订阅 v2 解密 | im/v1/messages | 交互卡片最适合做审批 UI |
| 企业微信 | AES 加密回调 | 主动 message/send（管 access_token） | 自建应用 vs 客服；Markdown 卡片 |
| 钉钉 | outgoing 机器人 HMAC | webhook / robot/send | session webhook 有时效 |

渠道差异速查：

| 渠道 | 入站 | 出站 | 特殊点 |
| --- | --- | --- | --- |
| 飞书 | 事件订阅 v2 解密 | im/v1/messages | 交互卡片最适合做审批 UI |
| 企业微信 | AES 加密回调 | 主动 message/send（管 access_token） | 自建应用 vs 客服；Markdown 卡片 |
| 钉钉 | outgoing 机器人 HMAC | webhook / robot/send | session webhook 有时效 |
| Web 聊天窗 | 直接 HTTP | **复用现有 `/chat/stream` SSE** | 前端套 EventSource，近乎白嫖 |
| IVR | ASR 转文本入 | 文本转 TTS | 要极简短答案——给 IVR 单独 `app.assistant.overrides` tone |

---

## SSO / OAuth 设计（已暂缓，备查）

决策：**不动 `ApiKeyAuthFilter`，平行加 `JwtAuthFilter`**，两条都汇到同一个 `TenantContext`
→ 下游 `@PreAuthorize`、限流、配额、审计**全部零改动**。

要点（待 IdP 确定后实施）：
- 验签优先用 Spring Security 原生 `oauth2ResourceServer().jwt()`（自带 JWKS 拉取/缓存/轮换），
  但**保留一个轻量 filter 专管 `TenantContext` 的 set/clear**（对齐 `ApiKeyAuthFilter` 的 finally 清理语义）
- claim → tenant 映射做成可配（`tenant-claim`）+ `ClaimMapper` 策略，覆盖三种常见形态：
  多租户 IdP（`tid`/`org_id`）、单 IdP 自定义 claim、按邮箱域名兜底
- scope claim 解出后走同样的 `SCOPE_` 前缀逻辑，对齐现有 RBAC

---

## 与其他文档的关系

- 已落地的业务平台基线（auth/限流/配额/审计/异步/推送） → `docs/production-hardening.md`
- 待完善项总览 / ROI 决策表 → `docs/roadmap.md`
- 运营基建（Prometheus/Grafana/Health） → `docs/observability.md`
- 项目导航 → `CLAUDE.md`
