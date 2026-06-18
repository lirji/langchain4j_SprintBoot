# 业务场景落地总览

这份文档把本仓库已经落到具体业务的**接入场景**汇总在一处，作为场景层的导航入口。
平台底座能力（多租户 / 限流 / 配额 / 审计 / 异步 / 推送 / 可观测）见 `docs/production-hardening.md`，
本文只讲「这些能力被组装成了哪些可交付的业务场景、各自怎么接、跑到什么程度」。

> 关联：平台基线 → `production-hardening.md`；待完善项 / ROI → `roadmap.md`；项目导航 → `CLAUDE.md`。

## 场景一览

| 场景 | 子能力 | 状态 | 详细文档 | 核心入口 |
| --- | --- | --- | --- | --- |
| **① 企业知识库问答** | 多租户 RAG + 文档 CRUD/版本 + 持久化 | ✅ 已落地并验证 | `docs/knowledge-base.md` | `POST /rag/documents`、`POST /chat` |
| **② 智能客服** | NL2SQL / ChatBI | ✅ 已落地并验证 | `docs/nl2sql.md` | `POST /chat/sql` |
| **② 智能客服** | 工作流编排（Flowable 审批） | ✅ 已落地并端到端验证 | `docs/workflow-integration.md` | `POST /workflow/refund/start` |
| **② 智能客服** | 渠道接入（飞书样板，M1.B） | ✅ 已落地（代码就位，默认关；出站/卡片需真应用联调） | `docs/workflow-integration.md` | `POST /channel/feishu/event` |
| **② 智能客服** | 语音渠道（turn-based 语音 Agent） | ✅ 已落地（代码就位，默认关；ASR/TTS 需配 provider key 联调） | `docs/voice-agent.md` | `POST /voice/chat` |

> **说明**：「客服场景」是一个由多块拼成的闭环。**已落地**：NL2SQL/ChatBI（自然语言查业务库）、工作流编排
> （退款等需人工审批的长流程）、飞书渠道（入站意图路由：退款/投诉→工作流，其余→对话 + 5s ack + 异步回推 + 审批卡片闭环）、
> 语音渠道（turn-based：音频→ASR→**共享客服大脑** `CustomerServiceBrain`→TTS→音频，复用同一套意图路由/工作流/RAG）。
> 飞书与语音两个渠道共用大脑：飞书代码就位但出站需真应用联调；语音代码就位 + 编排单测，但 ASR/TTS 需配 provider（云 OpenAI / 本地 whisper+tts）联调。
> 企微/钉钉/Web 靠复制飞书范式、实时全双工/电话 IVR 是语音的未来项，尚未编码。以本表「状态」列为准。

---

## ① 企业知识库问答（已落地）

**做什么**：把企业文档（PDF / Word / Excel / PPT / HTML / Markdown / 纯文本）按租户入库，
支持带来源引用的多轮问答，重启不丢、按租户硬隔离。

**这次落地补的两个硬缺口**：

1. **持久化向量库**：默认 `in-memory` 重启即丢 → 切 **Milvus**（`app.rag.store=milvus`）。
2. **PDF / Office 解析**：per-tenant 上传走 **Apache Tika**（`DocumentTextExtractor`），按内容嗅探类型不靠后缀。

两者 + Redis 持久化记忆 + grounding + multipart 放大统一收进 **`kb` profile**（`application-kb.yml`）。

**怎么跑**（详细验证步骤见 `docs/knowledge-base.md`）：

```bash
docker run -d -p 19530:19530 milvusdb/milvus:v2.4.10 milvus run standalone
docker run -d -p 6379:6379 redis
mvn spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=kb
```

**关键端点**：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/rag/documents` | per-tenant 上传文档（multipart），Tika 解析、写 tenant metadata、返回 docId/version |
| GET | `/rag/documents` | 列出本租户文档 |
| POST | `/chat` | 带 `X-Api-Key` 问答，回复含 `[doc=文件名#N]` 引用，疑似不被支撑时追加 `⚠️ 可信度提示` |

**关键文件**：`rag/lifecycle/{DocumentService,DocumentRegistry,DocumentTextExtractor}.java`、
`config/LangChain4jConfig.tenantScopedFilter`（检索层强制 AND tenantId 兜底隔离）、`application-kb.yml`。

**验证结论**：PDF 能问答、引用格式正确、租户 B 隔离生效、重启后 KB 仍在、重复上传不召回旧片段
（Milvus 删除/版本覆盖有一个需实测确认的坑，见 `docs/knowledge-base.md`）。

---

## ② 智能客服 · NL2SQL / ChatBI（已落地）

**做什么**：客服 / 运营用自然语言提问 → LLM 生成 SQL → 只读执行 → LLM 解读结果，
让不会写 SQL 的人也能查业务库（订单状态、退款记录等）。

**核心是 6 层 SQL 安全护栏**（`SqlGuard`，18 个单测覆盖）：

1. L1 只读账号　2. L2 语句白名单（仅 SELECT）　3. L3 表白名单　4. L4 强制 LIMIT
5. L5 执行超时　6. L6 租户谓词（强制注入 `tenant_id = ?`）

外加 Schema 注入（含中文枚举 distinct 值）+ few-shot 提升生成质量。

**怎么跑**（默认关闭，需 tool-calling 模型）：

```bash
# 准备 demo 库
mysql < src/main/resources/db/nl2sql-demo.sql
# 启用（注意多参数覆盖用 env var，别堆逗号——见 CLAUDE.md）
APP_NL2SQL_ENABLED=true mvn spring-boot:run
```

**关键端点**：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/chat/sql` | body `{"message":"..."}` → 返回 `{question, sql, rowCount, rows, answer, guardBlocked}` |

**关键文件**：`nl2sql/{NlToSqlService,SqlGuard,SqlQueryTool,SchemaProvider,Nl2SqlConfig}.java`、
`resources/db/nl2sql-demo.sql`。

**验证结论**：本地 4 用例全过；待做的 2.B（自修环 / 数字 grounding / eval `type:"sql"`）见 `docs/nl2sql.md`。

---

## ② 智能客服 · 工作流编排（已落地）

**做什么**：退款/改单/投诉升级等「挂起等人工审批」的长流程。抽工单 → priority 高（HIGH/CRITICAL）则
进人工审批（Flowable `UserTask` 挂起，引擎表持久化，期间服务重启不丢）→ 通过/驳回 → 生成答复。
低风险自动受理。

**怎么跑**（默认关闭，需一个可登录的 MySQL；assess/resolve 调模型需 Ollama 等 chat provider）：

```bash
WORKFLOW_DB_PASSWORD=<your-mysql-pwd> APP_WORKFLOW_ENABLED=true mvn spring-boot:run
```

**关键端点**：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/workflow/refund/start` | 发起（任意已认证 key）。返回 `{instanceId, status, reply, taskId, priority}`；自动受理 → `COMPLETED`，需审批 → `WAITING_APPROVAL` |
| GET | `/workflow/tasks` | 本租户待审任务（需 `SCOPE_approve`） |
| POST | `/workflow/tasks/{taskId}/complete` | body `{approved, comment}`（需 `SCOPE_approve`）→ 同步跑 resolve/reject → 返回 `{reply}` |
| GET | `/workflow/instances/{instanceId}` | 实例状态 + reply |

**关键文件**：`workflow/{WorkflowConfig,WorkflowService,ServiceTaskDelegates,WorkflowProperties}.java`、
`controller/WorkflowController.java`、`resources/processes/refund-approval.bpmn20.xml`。

**验证结论**：编译 + 单测通过；本地 MySQL + Ollama(qwen3:14b) **端到端跑通**三条分支（自动受理 / 批准 / 驳回）
+ RBAC + 租户隔离 + 审计落盘。3 处与原设计的落地偏差（依赖换 `flowable-spring`、数据源用 MySQL、
租户隔离走流程变量）及实跑记录见 `docs/workflow-integration.md`「实施纪要 / 端到端验证」。

## ② 智能客服 · 渠道接入（飞书，M1.B 已落地，默认关）

**怎么跑**：飞书开发者后台建自建应用 → 配事件订阅 URL `https://<公网>/channel/feishu/event` →
```bash
APP_CHANNEL_FEISHU_ENABLED=true APP_WORKFLOW_ENABLED=true WORKFLOW_DB_PASSWORD=root \
FEISHU_APP_ID=cli_xxx FEISHU_APP_SECRET=xxx FEISHU_VERIFICATION_TOKEN=xxx FEISHU_ENCRYPT_KEY=xxx \
mvn spring-boot:run
```

**关键端点**：`POST /channel/feishu/event`（握手 + 消息事件 + 卡片回调，安全链放行）。

**关键文件**：`channel/feishu/{FeishuController(在 controller/channel/),FeishuCrypto,FeishuIntent,FeishuClient,FeishuChannelService,FeishuReplyListener,FeishuConfig,FeishuProperties}` + `workflow/WorkflowTerminalEvent`。

**做了什么**：入站验签解密（AES-256-CBC + 签名）→ 意图路由（退款/投诉→refund 工作流，其余→Assistant 对话）→
`@Async` 处理 + 5s ack → 完成后主动回推（工作流终态经 `WorkflowTerminalEvent` → `FeishuReplyListener`）；
高风险审批推交互卡片，按钮回调 `complete`（人工审批 UI 零前端）。企微/钉钉/Web/IVR 靠复制此范式。

**验证结论**：`FeishuCrypto`（解密 round-trip + 验签）、`FeishuIntent`（意图分类）已纯逻辑单测通过；
出站发消息/卡片回调需接真飞书应用 + 公网回调联调（清单见 `docs/workflow-integration.md`「渠道（飞书）落地」）。
v1 单租户（所有入站归到 `app.channel.feishu.tenant`，默认 tenantA），多租户 `tenant_key→tenantId` 映射留后续。

---

## 与其他文档的关系

- 知识库问答部署与验证 → `docs/knowledge-base.md`
- NL2SQL/ChatBI 安全护栏与链路 → `docs/nl2sql.md`
- 客服工作流 / 渠道 / SSO 接入设计 → `docs/workflow-integration.md`
- 平台底座基线（auth/限流/配额/审计/异步/推送） → `docs/production-hardening.md`
- 待完善项 / ROI 决策表 → `docs/roadmap.md`
- 项目导航 → `CLAUDE.md`
