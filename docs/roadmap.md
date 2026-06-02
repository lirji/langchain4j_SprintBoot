# Roadmap / 待完善项

项目已经"够生产用"。下面这些是从"能跑"到"完善"的差距，按 ROI 分档。
每条带工作量估计和**做不做的判断条件** —— 不是 todo 越多越好，是为了避免"觉得该做但没做"的隐性焦虑。

最后更新：2026-05-28（业务化基线 #1–#8 落地后）。

---

## A. 真该做

**✅ 全部完成于 2026-05-27**。

| 项 | 状态 | 落地说明 |
| --- | --- | --- |
| **核心 path 单元测试** | ✅ | 3 个测试类，18 个 case：`MultiAgentServiceTest`（拓扑排序 7 case）+ `AssistantPropertiesTest`（resolve 部分覆盖 6 case）+ `CaseAggregateTest`（统计聚合 5 case）。覆盖最容易回归的算法层 |
| **Critic 独立 temp=0 ChatModel** | ✅ | `ReflexionConfig.critic()` 改成调 `LlmConfig.buildJudgeChatModel(props)`，复用 Judge 的 trick（不注册成 ChatModel Bean 避免冲突） |
| **API key 安全化** | ✅ | `application.yml` 里 `DEEPSEEK_API_KEY:sk-...` hardcode 改成 `${DEEPSEEK_API_KEY:}` 空 fallback + 注释告警；**原 key 已轮换** |
| **eval auto-ingest** | ✅ | 加 `app.eval.auto-ingest: false` yml 开关；`EvaluationRunner` 用 `AtomicBoolean` lazy 触发，第一次 `run` 时 ingest 一次，后续不重复 |

---

## B. 想做有意思（提升上限）

| 项 | 说明 | ROI |
| --- | --- | --- |
| ~~**History-aware retrieval**~~ | ✅ 完成于 2026-05-27：装 LangChain4j 内置 `CompressingQueryTransformer` + 自实现 10 行 `ChainedQueryTransformer` 让 history-aware 和 expansion 能 chain（顺序：compress→expand）。实测本项目 corpus 小 + nomic-embed-text 收益不显著，跟 expansion 同类。见 docs/qa.md Q7 | ~~小~~ |
| ~~**Query expansion**~~ | ✅ 完成于 2026-05-27：装 LangChain4j 内置 `ExpandingQueryTransformer`，`app.rag.query-expansion.{enabled,n}` 默认关。实测本项目 corpus 小 + nomic-embed-text 对同义改写已经包容，**baseline 和 expansion 召回到一样的 chunk**。真正受益场景：大 corpus + 模糊 query + 跨语言。见 docs/qa.md Q6 | ~~小~~ |
| **Re-rank 默认开 + 跑一次 eval 对比** | 项目有 `OllamaLlmScoringModel` / `JinaScoringModel` 但默认关，从没量化过收益 | 30 分钟跑 eval 对比 rerank on/off，看 passRate 漂动 |
| ~~**跨 endpoint 的 stream response**~~ | ✅ 完成于 2026-05-27：加 `/chat/multi-agent/stream` + `/chat/reflexive/stream`，SSE 按阶段 emit `plan` / `worker-result` / `synthesis-token` / `done`（multi-agent）和 `attempt-start` / `answer-token` / `critique` / `done`（reflexive）。Worker / Critic 仍非流式（结构化输出 + 多 worker 交错难处理）。见 docs/qa.md Q5 | ~~中~~ |
| ~~**Chunking 策略优化**~~ | ✅ 完成于 2026-05-27：加 `MarkdownHeaderSplitter`（按 `##` 切节，超长 fallback 到 recursive）+ yml 配置化 `app.rag.chunking.{strategy,max-chars,overlap}`。**实测对本项目结构化 markdown 收益显著**：同一 query 答出 provider 数从 2 → 5（完整召回整个 LLM Provider section）。6 个单元测试。见 docs/qa.md Q8 | ~~中~~ |
| ~~**RAG 事实幻觉事后校验（grounding）warn 模式**~~ | ✅ 完成于 2026-05-28：`app.rag.grounding.*`（默认关）。Layer 0 引用 id 完整性核对（零 LLM）+ Layer 1 `GroundednessChecker` faithfulness（temp=0，RAGAS 拆断言），命中追加 `⚠️ 可信度提示`。挂 `/chat` + `/chat/category`，仅检索到 source 时跑。7 个单元测试 + 2 条 `grounded` eval case。**剩余**：`on-fail=refuse/regenerate`（v1 只 warn）、流式路径、Layer 2 句级 NLU 归因。详见 CLAUDE.md "RAG 事实幻觉事后校验" 节 | ~~中~~ |

**做不做的判断条件**：B 看你下一步项目走向 —— 还在钻 prompt + RAG 就做"Re-rank 跑 eval 对比"（最快出价值），符合本项目反复推的"调一处看变化"方法论。

---

## C. 工程化 / 防御性（scale-up 才必要）

**2026-05-28 业务化基线（#1–#7）一次性落地**：多租户隔离 / 限流 / token 配额 / 文档生命周期 /
prompt injection / 审计日志 / 长任务异步化。详见 `docs/production-hardening.md`。

| 项 | 状态 | 触发条件 / 落地说明 |
| --- | --- | --- |
| **多租户隔离** | ✅ | `X-Api-Key` 鉴权 + `TenantContext` ThreadLocal + RAG metadata filter + ChatMemory key 前缀。见 production-hardening #1 |
| **限流** | ✅ | Bucket4j 内存桶，per-(tenant, family)，429 + Retry-After。见 production-hardening #2 |
| **Token 配额（成本）** | ✅ | 日 token 预算，listener.onResponse 回填。见 production-hardening #3。**升级 cost-based（USD）** 留作触发条件：真开始烧云端 API 钱 |
| **文档生命周期管理** | ✅ | per-tenant CRUD `/rag/documents`，docId = SHA-256(tenant+name)。见 production-hardening #4 |
| **Prompt injection 防护** | ✅ | 12 条 bilingual 规则 + 可选 LLM 分类器；`@InputGuardrails` 挂主 Assistant。见 production-hardening #5。从 E 节"故意不做"挪到这里 |
| **审计日志** | ✅ | `logs/audit.jsonl` per-event JSON，Filebeat 采集即可。见 production-hardening #6 |
| **长任务异步化** | ✅ | `/chat/multi-agent/async` + `/tasks/{id}`，复用 multiAgentExecutor + TTL 24h。见 production-hardening #7 |
| **Webhook + SSE 推送** | ✅ | HMAC-SHA256 签名 + 指数退避重试 + SSE 长连接。见 production-hardening #8 |
| 熔断（Resilience4j） | ⏳ | 真上线 vLLM 后偶发 5xx 频繁时 |
| Provider fallback（主 vLLM 挂切云端） | ⏳ | SLA 要求 99.9%+ |
| API key → Vault / K8s Secret | ⏳ | 跟运维流程对齐时（目前 yml seed key 已经走 env override） |
| CI 集成（GitHub Actions 跑 eval） | ⏳ | repo 有协作者 + PR 流程时 |
| 多实例化（Redis-backed state） | ⏳ | 真上多实例 / K8s 多 pod 部署。Token tracker / Document registry / Task store 当前是内存，注释里已写好切 Redis 的扩展点 |

**做不做的判断条件**：剩下的 ⏳ 仍是"等真正需要再加"。每条触发条件明确，等到了再做就行。

> **知识库落地（2026-06-02）**：新增 `kb` profile（`application-kb.yml`）+ Apache Tika 解析 PDF/Office 上传。
> 把"持久化向量库（Milvus）+ 持久化记忆（Redis）+ grounding"一次性拧到生产基线 —— 上面"多实例化/持久化"
> 相关 ⏳ 在知识库场景下已给出落地路径。详见 `docs/knowledge-base.md`。

> **下一阶段两个业务场景（2026-06-02）**：
> - **#2 NL2SQL / ChatBI** → `docs/nl2sql.md`（自然语言查库 + 6 层 SQL 安全护栏）。**✅ Milestone 2.A 已落地并验证**
>   （MySQL demo 库 + 18 个 SqlGuard 单测 + 4 条端到端用例全过）。2.B（自修环 / 数字 grounding / eval `type:"sql"`）待按信号补
> - **#1 智能客服全闭环** → `docs/workflow-integration.md`（Flowable 7.1.0 工作流 + 人工审批 + 飞书渠道；设计已定，待实施）
>
> 两者解耦、各自 `@ConditionalOnProperty` 默认关。剩余顺序 **#1.A（工作流）→ #1.B（飞书）**。

---

## D. 现有 prompt 自身还能再调

| 项 | 说明 |
| --- | --- |
| **大规模 case 集**（30 → 100+） | 现在大多稳定通过，Judge 信号比较弱。加 adversarial（多语言混合、长输入、模糊指令、刻意误导）让 Judge 真能扣分 |
| **`format-table` case 设计 bug 修** | 早就发现的 mustInclude 跟模型偏好冲突（要 404 但模型偏给 400/401/403） |
| **DAG eval 多 case** | 现在只 1 个 DAG case（多 case 才能验证拓扑算法各种形态），应该加菱形依赖、一对多依赖、3 层链 |
| **few-shot 例子换成 production 数据** | 现有 Extractor / Planner 例子是手写，可以从真实业务案例里挑 3-5 个真实场景 |

**做不做的判断条件**：D 适合 prompt 工程兴趣不减的时候做。当前 eval 信号在大部分 case 都是 1.0，扩 case 集是让 Judge 重新有"扣分空间"的最直接方式。

---

## E. 没做但故意不做（决定记录）

| 项 | 为什么不做 |
| --- | --- |
| Web UI | 项目定位是脚手架不是产品，REST API 够了 |
| 自动 prompt 优化 loop（用 eval 当 reward） | 研究性 > 工程性，没量产价值。学术项目可以试 |
| ~~完整的 jailbreak / prompt injection guard~~ | ✅ 2026-05-28 改主意做了：12 条 bilingual 规则 + 可选 LLM 分类器，覆盖 OWASP LLM01:2025 主要类型。重型框架（NeMo Guardrails / Guardrails AI）仍然不引入，但本项目自实现的轻量版够"业务可上"。见 production-hardening #5 |
| Trace store（统一 query routing + DAG + reflexion 的可视化）| 想做但优先级低，需要新加 trace 数据库 + 前端，工程量大于现有 observability 全套 |
| OpenTelemetry distributed tracing | Spring Boot 3 内置 Micrometer Tracing 可以挂上，但目前 traceId in MDC 已经够本地调试 |
| GraphQL endpoint | REST 满足所有用例，加 GraphQL 是 over-engineering |

---

## 推荐路径

按"出现下面这个信号就做对应那个"的方式选：

| 信号 | 该做 |
| --- | --- |
| 改 LlmConfig / 装配链时心里没底 | A 的"单元测试" |
| 反思（`/chat/reflexive`）的 critic 分数对同样答案不稳 | A 的"Critic temp=0" |
| Repo 准备给协作者 / 截图 / commit 时 | A 的"API key 安全" |
| `/eval/run` 启动后第一次 RAG case 全 fail | A 的"auto-ingest" |
| 想知道 rerank 到底值不值得开 | B 的"rerank 跑 eval" |
| 用户在 multi-agent 等 30s 抱怨 | B 的"stream response" |
| RAG 多轮对话用户用代词指代时回答跑偏 | B 的"history-aware retrieval" |
| Eval passRate 总是 ~95-100%，没扣分空间 | D 的"大规模 case 集" |
| 真上 vLLM 看到偶发 5xx | C 的"熔断" |
| 真开始烧云端 API 钱 | C 的"Token 配额 → cost-based 升级（USD）" |
| 部署到多 pod 出现状态不一致 | C 的"多实例化（Redis-backed state）" |

没出现的信号就不做。

---

## 关联文档

- 项目历史 → `PROMPT_JOURNEY.md`（看每一轮怎么做的）
- 业务化基线 #1–#7 落地 → `docs/production-hardening.md`
- 已完成的运营基建 → `docs/observability.md`
- 问答 → `docs/qa.md`
- 项目导航 → `CLAUDE.md`
