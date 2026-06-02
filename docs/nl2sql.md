# NL2SQL / ChatBI 落地设计

把"自然语言 → SQL → 执行 → 自然语言解读"做成一条**受控**链路。这是项目两条主线的直接延展：
**「`@Tool` 描述即模型决策依据」**（让模型知道何时该查库、怎么查）+ **「grounding 防幻觉」**
（答案里的数字必须来自查询结果，不许编）。

> 状态：**Milestone 2.A 已落地并本地验证通过（2026-06-02）**。整套 `@ConditionalOnProperty(app.nl2sql.enabled)`
> 默认关，不影响现有启动路径。2.B（自修环 / 数字 grounding / eval `type:"sql"`）待按信号补。
> 与 `docs/workflow-integration.md`（#1 智能客服）并列为下一阶段两个业务场景。

---

## 为什么做这个

企业最高频的内部刚需之一：业务/运营不会写 SQL，但天天要问"上月华东区退款 top10 客户""昨天各渠道
下单量环比"。把它交给 LLM = 自助 BI。本项目已有的能力让它几乎是纯组装：

| 复用现有能力 | 用在哪 |
| --- | --- |
| Tools / Function Calling（`@Tool` 自动发现） | 一个只读 `SqlQueryTool` 执行查询 |
| 结构化输出 + few-shot 范式 | SQL 生成的 prompt 工程（参考 `Extractor`/`Planner` 的 3 例打地基） |
| grounding（Layer 0 确定性校验） | 答案数字 ∈ 查询结果，否则判"编造数字" |
| Reflexion 的 `Critic` 自修思路 | SQL 执行报错时喂回错误自修一次 |
| 多租户 `TenantContext` | 自动注入 `WHERE tenant_id=?` / per-tenant 库 |
| eval harness（`type` dispatch） | 加 `type:"sql"` 把 NL2SQL 纳入回归 |

**真正净新增的只有两块**：`SchemaProvider`（把表结构喂进 prompt）+ 一层**硬核 SQL 安全护栏**。

---

## 阶段决策（已定）

| 决策点 | 决策 | 理由 |
| --- | --- | --- |
| **生成方式** | LLM function calling（AiService + `@Tool`），不自己解析 | 复用现有 `@AiService` 装配链，模型自己决定调不调、传什么 SQL |
| **执行安全** | **独立只读 DataSource + 语句白名单 + 强制 LIMIT + 超时**，多层兜底 | SQL 注入/全表扫描是这个场景唯一的真风险，宁可层层冗余 |
| **dev 数据库** | **MySQL + demo 种子**（订单/客户/退款），`createDatabaseIfNotExist` 自动建库 | 复用已有 `mysql-connector-j`；prod 接真实只读库只改 `app.nl2sql.datasource.*` + seed-script 置空 |
| **表暴露范围** | **白名单**，不 dump 整库 | 控制 prompt 长度 + 缩小攻击面 + schema 精准 |
| **租户隔离** | 业务表带 `tenant_id` 列时自动注入 WHERE；或 per-tenant schema | 对齐现有 `TenantContext` 语义 |
| **增强（2.B）** | 先做 2.A MVP，自修环 / grounding / eval 看信号再加 | 不堆半成品开关 |

---

## 链路总图

```
POST /chat/sql  {question}
   │  (经过现有过滤器链: TraceId / ApiKey / RateLimit / TokenBudget → TenantContext 有值)
   ▼
NlToSqlService
   ├─ SchemaProvider.schemaText()   ── 白名单表/列/注释/外键 → 紧凑 schema 文本
   ▼
SqlAssistant (@AiService, system prompt 内嵌 schema + 3 例 few-shot)
   │  LLM 决定调用 SqlQueryTool 并生成 SELECT
   ▼
SqlQueryTool (@Tool, 只读)  ──── 安全护栏（下节，多层） ───► 只读 DataSource
   │  返回 rows (markdown table / JSON)
   ▼
LLM 拿 rows 生成自然语言解读
   ▼
[2.B] grounding: 答案数字 ∈ rows? 否则追加 ⚠️ 提示
   ▼
返回 {sql, rows, answer}   ← sql 一并回传，前端可审计/复跑
```

---

## SQL 安全护栏（本场景成败关键，多层冗余）

任何一层都能独立拦住恶意输入，叠加是为了纵深防御。

| 层 | 机制 | 拦住什么 |
| --- | --- | --- |
| **L1 数据库账号** | 专用**只读账号**（`GRANT SELECT` only，无 DDL/DML 权限） | `DROP`/`DELETE`/`UPDATE` 在 DB 层直接被拒，即便绕过上层也无害 |
| **L2 语句白名单** | 解析后只允许**单条 `SELECT`**：禁 `;` 多语句、禁 `INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE/GRANT`、禁 `UNION` 注入、禁 `--`/`/* */` 注释绕过 | `'; DROP TABLE orders;--` 这类注入 |
| **L3 表白名单** | 解析 FROM/JOIN 的表名，必须 ∈ `SchemaProvider` 暴露的白名单 | 越权查 `users`/`act_*`/系统表 |
| **L4 强制 LIMIT** | 无 `LIMIT` 自动追加 `LIMIT 1000`（可配 `app.nl2sql.max-rows`） | 全表扫描拖垮库 |
| **L5 statement 超时** | JDBC `statement.setQueryTimeout(5s)`（可配） | 慢查询/笛卡尔积 |
| **L6 租户隔离** | 业务表带 `tenant_id` 时强制注入 `WHERE tenant_id = :current`；缺租户列的表不进白名单 | 跨租户数据泄露 |

> 设计取舍：**L2/L3 的 SQL 解析**用轻量办法（正则 + 关键字黑名单 + 简单 FROM 提取）起步，
> 够拦常见注入；真要严谨可引 JSqlParser 做 AST 级校验，但先不引依赖（对齐项目"按信号加"原则）。
> **即便 L2/L3 被绕过，L1 只读账号 + L4/L5 仍兜底**——这就是多层的意义。

---

## 计划新增结构

```
nl2sql/
├── Nl2SqlProperties.java        @ConfigurationProperties(app.nl2sql.*)：enabled / datasource / max-rows / timeout / 表白名单
├── Nl2SqlConfig.java            @ConditionalOnProperty 装配只读 DataSource + SqlAssistant（AiService）
├── SchemaProvider.java          读白名单表的列/注释/FK，拼成紧凑 schema 文本喂 prompt
├── SqlGuard.java                L2/L3/L4 校验 + 改写（白名单 / 注入拦截 / 补 LIMIT / 注租户）
├── SqlQueryTool.java            @Tool 只读执行；@Tool 描述严格按 DateTimeTool 规格写清何时调/参数/返回
├── SqlAssistant.java            @AiService 接口：system prompt 内嵌 {{schema}} + 3 例 few-shot
└── NlToSqlService.java          编排 schema 注入 → SqlAssistant → grounding → 组装 {sql, rows, answer}
controller/Nl2SqlController.java POST /chat/sql
src/main/resources/db/nl2sql-demo.sql   MySQL dev demo 库种子（orders/customers/refunds + 只读账号）
```

依赖现有：`TenantContext`、`AuditLogger`、grounding 的 `RetrievedSourcesContext` 套路（这里换成
`QueryResultContext` 存本轮 rows 供 Layer 0 校验）、`LlmConfig`（SqlAssistant 复用主 ChatModel）。

---

## REST 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/chat/sql` | body `{"question":"上月退款 top5 客户"}`；返回 `{sql, rows, answer}`。需 `app.nl2sql.enabled=true` |

`sql` 一并回传是刻意的：**可审计 + 前端可"查看/复跑 SQL" + debug 时一眼看出是生成错还是解读错**。

审计挂点（复用 production-hardening #6）：`AuditEventType` 新增 `nl2sql.query`（记 question + 最终
SQL + 行数 + 是否被护栏拦截），合规与成本追踪都要。

---

## 实施里程碑

### Milestone 2.A — 只读 SQL Tool + Schema 注入（MVP）

1. pom 加 `spring-boot-starter-jdbc`（驱动复用已有 `mysql-connector-j`）；`Nl2SqlProperties` + `@ConditionalOnProperty(app.nl2sql.enabled)` 默认关
2. `application.yml` 加 `app.nl2sql.{enabled,datasource,max-rows,timeout,allow-tables}` 配置块
3. `resources/db/nl2sql-demo.sql`：orders / customers / refunds 三表 + 种子数据（带 `tenant_id` 列演示隔离）
4. `SchemaProvider` → `SqlGuard`（L2/L3/L4/L6）→ `SqlQueryTool`（只读执行 + L1 账号 + L5 超时）
5. `SqlAssistant` system prompt：内嵌 schema + 3 例 few-shot（典型聚合 / 带 join / 拒答非查询意图）
6. `NlToSqlService` + `Nl2SqlController`
7. 单测：`SqlGuard` 是纯逻辑——注入拦截 / LIMIT 补全 / 表白名单 / 租户注入，**这是回归最该钉的算法层**

**验收**：MySQL demo 库（`createDatabaseIfNotExist` 自动建）能答"上月退款 top5 客户"；
`'; DROP TABLE orders`、`SELECT * FROM users`、`UPDATE ...` 全被护栏拒并安全回话；
无 LIMIT 的查询被自动加上；只读账号 `nl2sql_ro` 即便绕过护栏也无写权限（L1）。

#### 本地验证记录（2026-06-02，已跑通）

demo 种子的两个租户对齐 production-hardening 的两个 demo key：`tenantA`（主数据集，key
`dev-key-tenantA-admin`）/ `tenantB`（隔离对照，key `dev-key-tenantB-readonly`）。模型用 `qwen3:14b`
（Ollama，支持 tool-calling）。四条端到端用例全过：

| 用例 | 输入 | 结果 |
| --- | --- | --- |
| 聚合 + join | tenantA「退款金额最高的3个客户」 | 自动生成 `JOIN ... GROUP BY ... ORDER BY ... LIMIT 3`，**两表都自动带 `tenant_id='tenantA'`**（L6），返回赵六 5400 / 李四 1870 / 张三 800 ✅ |
| 租户隔离 | tenantB 问同样问题 | SQL 自动 `tenant_id='tenantB'`，只返回 ACME-A 9999，**看不到 tenantA 的数据** ✅ |
| 越界拒答 | 「各供应商的库存周转率」 | 不调工具，`sql=null`，答"可查询的数据集里没有供应商或库存相关的表" ✅ |
| 中文枚举（坑3） | 「状态是已退款的订单」 | 模型用对 `status='已退款'`（来自 SchemaProvider distinct 值），返回 4 笔 8400，**无 LIMIT 被 L4 自动补 `LIMIT 1000`** ✅ |

**踩到的坑（已记录，便于复跑）**：

1. **模型要支持 tool-calling**：项目默认 `llama3.1` 本机没拉，换 `qwen3:14b`。NL2SQL 走函数调用，纯文本模型不行。
2. **`-Dspring-boot.run.arguments=a,b` 逗号分隔只稳定生效第一个**：多参数用 env var 传
   （`APP_NL2SQL_ENABLED=true APP_LLM_OLLAMA_MODEL_NAME=qwen3:14b NL2SQL_DB_PASSWORD=root`）。
3. **安全默认开**：`/chat/sql` 要带 `X-Api-Key`；出错时本项目把 500 经 /error 二次 dispatch 掩成 403
   （filter 已清 `TenantContext` → 该次 dispatch 变 anonymous），看 `logs/audit.jsonl` 的 `llm.error` 才是真因。
4. **只读账号连 in-mem 用 H2 时** 会撞 admin-only 的 `SET DB_CLOSE_DELAY`——这也是最终选 MySQL 的诱因之一；
   MySQL 下 admin url 带 `createDatabaseIfNotExist`、只读 url 不带即可。

### Milestone 2.B — 准确性与可信（看信号再加）

| 增强 | 复用 | 触发信号 |
| --- | --- | --- |
| **SQL 自修环** | Reflexion `Critic`/replan 思路：执行报错→错误喂回 LLM 自修一次（`app.nl2sql.repair.enabled`） | demo 里复杂 join 经常一次生成跑不通 |
| **数字 grounding** | grounding Layer 0：答案里的数字 ∈ rows，否则追加 `⚠️ 可信度提示`，走 `QueryResultContext` | 模型解读时把数字记串/四舍五入编造 |
| **eval 纳入回归** | `EvalCase` 加 `type:"sql"` dispatch（`EvaluationRunner.invokeByType` 加一支）；黄金集放几条 NL→断言 | 想 A/B schema prompt / few-shot 改动的真实差异 |

---

## 这个场景特有的坑（实施前注意）

1. **第二个 DataSource 不能污染 Flowable / 主库**：#1 也要引 `spring.datasource`（Flowable 用）。
   NL2SQL 的只读库是**独立第二 DataSource**，必须用 `@Qualifier` 显式区分，别让 Spring 自动注入串台。
   两个特性同开时，确认 `@Primary` 归属清晰（建议主 DataSource = Flowable，NL2SQL 用命名 Bean）。
2. **schema 文本的 token 成本**：白名单表多了 prompt 会胀。控制在"核心几张表"，列注释精简；
   大库场景未来可做"先让 LLM 选相关表（routing）再注入子集 schema"，v1 不做。
3. **中文列名/枚举值**：业务库常有中文枚举（`status='已退款'`）。few-shot 里要给一例带中文 WHERE 值，
   否则模型容易猜英文。`SchemaProvider` 最好把关键枚举列的 distinct 值也带上（少量）。
4. **租户列不一致**：不是所有表都有 `tenant_id`。L6 策略：有租户列→强制注入；无租户列的表→
   要么不进白名单，要么标记为"全局只读表"（如字典表）显式放行。配置里区分。

---

## 与其他文档的关系

- 另一个并行业务场景（智能客服 / 工作流 / 渠道） → `docs/workflow-integration.md`
- 已落地的业务平台基线（auth/限流/配额/审计/异步/推送） → `docs/production-hardening.md`
- grounding（Layer 0/1 幻觉校验，本设计复用其确定性校验套路） → `CLAUDE.md`「RAG 事实幻觉事后校验」节
- eval harness（`type` dispatch，本设计新增 `sql` type） → `CLAUDE.md`「评测 Harness」节
- 待完善项总览 / ROI 决策表 → `docs/roadmap.md`
