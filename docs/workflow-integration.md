# 业务落地接入设计：渠道 / SSO / 工作流编排

这份文档记录"把项目落到实际客服 / 知识库场景"的接入设计与决策。
区别于 `docs/production-hardening.md`（已落地的业务平台基线 #1–#8），本文是 **下一阶段的接入规划**，
目前处于"设计已定、分块实施中"状态。

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
| **实施顺序** | **先做工作流**，再做飞书渠道 | 用户指定 |

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
| `POST` | `/workflow/refund/start` | 启流程；自动分支立即返回 `{instanceId, status:COMPLETED, reply}`；需审批则 `{instanceId, status:WAITING_APPROVAL, taskId}` |
| `GET`  | `/workflow/tasks` | 本租户待审 UserTask 列表（`taskTenantId` 过滤） |
| `POST` | `/workflow/tasks/{taskId}/complete` | body `{approved, comment}` → `taskService.complete(...)` → 同步跑 resolve/reject → 返回 `{reply}` |
| `GET`  | `/workflow/instances/{instanceId}` | 实例状态 + reply（完成后） |

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

### 待确认 / 实施前 TODO

- [x] **Flowable 版本钉定**：`flowable-spring-boot-starter-process:7.1.0`（SB 3.3.5 稳妥；7.2.0 不兼容 SB4，故不选最新）
- [ ] H2（dev）依赖 + `spring.datasource` 配置（与 #2 NL2SQL 的只读库用 `@Qualifier` 区分，见 `docs/nl2sql.md` 坑 1）
- [ ] `@ConditionalOnProperty(app.workflow.enabled)` 包住整套，默认关，不影响现有启动
- [ ] BPMN 部署方式：classpath 自动部署 vs 启动时 `repositoryService.createDeployment()`

---

## 渠道（飞书）设计（下一阶段，已规划未实施）

样板选飞书：交互卡片最全，审批按钮直接做工作流 UI，零额外前端。

计划新增 `controller/channel/`：
- `FeishuController` — 事件订阅回调入口（URL 验证握手 + 消息事件 + 卡片回调）
- `FeishuCrypto` — `Encrypt` AES 解密 + `verification token` 校验（思路同现有 `WebhookSigner` 的 HMAC，但算法不同）
- `FeishuClient` — 出站 `im/v1/messages` + 卡片，管 `tenant_access_token` 缓存刷新
- `FeishuReplyListener` — `@EventListener<TaskEvent>`，任务终态时回推飞书（与现有 `WebhookDispatcher` 并列）

4 个关键点：
1. **入站验签解密**：事件订阅 v2 解密 + URL 验证握手原样回 `challenge`
2. **`tenant_key` → `tenantId`**：飞书企业身份天然是租户；`chatId = "feishu:" + open_id`，套现有
   `chat:mem:tenantA:feishu:xxx` 隔离 → 多轮记忆直接生效
3. **异步 ack + 主动回推**（飞书要求 ~5s 内响应，LLM 常超）：收消息 → 投 `AsyncTaskService` → 立即 200
   → `FeishuReplyListener` 监听终态 → `FeishuClient` 主动发。**复用 #7/#8 事件机制，仅多一个监听器**
4. **审批卡片闭环**（Flowable ↔ 飞书）：UserTask 推带"通过/驳回"按钮的交互卡片 → 点击回调到
   `FeishuController` → 调 `/workflow/tasks/{id}/complete` → 流程推进完成 → 回推用户。**人工审批 UI 零前端**

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
