# Roadmap / 待完善项

项目已经"够生产用"。下面这些是从"能跑"到"完善"的差距，按 ROI 分档。
每条带工作量估计和**做不做的判断条件** —— 不是 todo 越多越好，是为了避免"觉得该做但没做"的隐性焦虑。

最后更新：2026-06-25（续做 chunking 四项：`parent-child` / `semantic` 两种新切分策略 + Contextual Retrieval 上下文前缀 + `ChunkMetrics` 切分质量打点，见下「B. 想做有意思」表的「Chunking 策略优化」行，同步落到 `CLAUDE.md`/`CAPABILITIES.md`/`docs/observability.md`/`docs/rag-interview-notes.md`/`application.yml`）。上一轮 2026-06-17：代码审查驱动的优化共 20 项落地，见下「已落地优化 2026-06-16 / 06-17（一/二/三）」四节：记忆并发+异步 / chunking / prompt caching+指标 / eval 三类黄金集+baseline CI / nl2sql 数字 grounding+自修上限 / tools 错误处理 / 流式 PII 后处理 / A2A input-required+断连取消。

---

## 已落地优化 2026-06-16

一轮针对「记忆 / Chunking / RAG / A2A / eval」的代码审查，落地了其中 5 项可快速修的（bug 修复 + 低成本高收益），按优先级：

| # | 类别 | 改了什么 | 文件 | 性质 |
| --- | --- | --- | --- | --- |
| 1 | **RAG 配置失效 bug** | `candidateContentRetriever`（rerank 开启时的候选召回器）的 `minScore` 原写死 `0.3`，无视 `app.rag.min-score`。改成读配置，与 `directContentRetriever` 对齐 —— 开 rerank 后该项配置终于生效 | `config/LangChain4jConfig.java` | bug fix |
| 2 | **记忆并发丢更新** | `SummarizingChatMemory.add()` 的「读→追加→压缩→写」非原子，同一 `chatId` 并发请求（A2A / Webhook / 多标签页）会互相覆盖丢消息。加 per-id 静态锁串行化整个 RMW，`clear()` 进锁并回收锁条目。**限单 JVM**：多副本 + Redis store 仍需 Redis 层乐观锁（WATCH/MULTI）或分布式锁 | `memory/SummarizingChatMemory.java` | bug fix |
| 3 | **Anthropic prompt caching** | chat + streaming 两个 Anthropic builder 接上 `cacheSystemMessages` / `cacheTools`，新增 `app.llm.anthropic.cache-system-messages` / `cache-tools` 开关（默认开）。长 system prompt（5 段 `SYSTEM_PROMPT`）+ 工具 schema 命中缓存后，后续同前缀请求输入 token 按 cache-read 计费（约 1 折）。OpenAI 系是服务端自动缓存，无需 flag | `config/LlmConfig.java` | 降本 |
| 4 | **rerank 后阈值可配** | `ReRankingContentAggregator.minScore` 原写死 `0.0`（重排后一个低分候选都不丢）。提为 `app.rag.rerank.min-score`（默认 `0.0` 保持现状），调高即可剔除重排打分低于阈值的无关 chunk。与召回侧 `app.rag.min-score` 正交（那个治「召回什么」，这个治「重排后留什么」） | `config/LangChain4jConfig.java` | 质量 |
| 5 | **摘要确定性** | `summary` 窗口模式的摘要器原用主 ChatModel（temp 0.7），同一段历史每次压出不同摘要 → 记忆漂移。改用 `buildJudgeChatModel`（temp=0），跟 Judge/Critic 同思路；仅 summary 模式惰性构建，不注册成 Bean（避免多 ChatModel Bean 冲突） | `config/ChatMemoryConfig.java` | 质量 |
| 6 | **cache token 指标（收尾 #3）** | `MetricsChatModelListener` 原只打 input/output token，开了 prompt caching 后无法量化命中率。新增 `instanceof AnthropicTokenUsage` → 多打 `type=cache_read`（命中缓存、约 1 折计费）/ `cache_write`（建缓存、约 1.25×）两个 counter。OpenAI 系无此字段自然跳过。这样 Grafana 能算缓存命中率 = cache_read /(input) | `observability/MetricsChatModelListener.java` | 可观测 |

验证：`mvn compile` + 95 个单测全过。

### 第三档核实结论（2026-06-16）

对之前「没审到、只是推测」的包做了源码核实，把「潜在」证伪/证实：

**证伪（本就扎实，不动）**：
- `security` token 配额 `TokenBudgetTracker` —— `AtomicReference.updateAndGet` 原子，无 #1A 式竞态
- `security` 限流 `RateLimiterRegistry` —— Bucket4j + qpm 编进 cache key 规避 rebuild
- `async` `WebhookDispatcher` —— 指数退避 + 4xx 不重试 + HMAC 签名 + deliveryId + audit，完整
- 三处都带「多副本换 Redis distributed proxy」注释

**证实（仍是待办）**：
- nl2sql 数字 grounding（docs 2.B）：自修环地基已在（`SqlQueryTool` 报错返回文本而非抛异常），但缺 ① 答案数字是否真来自查询行的校验 ② 自修环轮数上限（坏 SQL 可反复重试烧 token）
- tools 错误处理不统一：`SqlQueryTool` 返回可纠错文本，但 `DateTimeTool` 的 `ZoneId.of`/`daysUntil` 直接抛异常中断 chat 回合 —— 约定该统一为「返回可纠错文本」

**审查中识别但本轮未做的**（留作后续，触发信号见下表风格判断）：

- 记忆：阈值仍按消息条数而非 token；摘要会累积膨胀无上限
- A2A：streaming 路径无 `input-required` 状态（多轮 Task 不可表达）；client 断连未取消 `TokenStream`（仍烧 token）
- eval：缺 NL2SQL（`type:"sql"`）/ A2A / workflow 的黄金集；无提交进仓库的 baseline JSON 做 CI 门禁
- 横切：`/chat/stream` 和 A2A stream 跳过 guardrail + grounding 后处理（已知，流式后处理需缓冲整段）

---

## 已落地优化 2026-06-17

接上一节，继续做 chunking 三处 + 摘要异步化（带单测）：

| # | 类别 | 改了什么 | 文件 | 性质 |
| --- | --- | --- | --- | --- |
| 7 | **Chunking：自适应标题层级** | `MarkdownHeaderSplitter` 原只切 `##+` → 纯 `#`(H1) 分级文档一刀不切成巨块。改为自适应：有 `##+` 按它切（历史行为不变），否则退到 `#`，都没有才整篇 1 段。`#` 在有 `##` 时仍不作边界（旧测试守住） | `rag/MarkdownHeaderSplitter.java` | 质量 |
| 8 | **Chunking：极小 section 合并** | 「`## 标题`+一行」这种碎块单独入库污染检索。新增 `app.rag.chunking.min-section-size`（yml 默认 120，代码默认 0=关），把连续不足阈值的小 section 向后并块，尾部残余并进上一块；大 section 各自独立不被合并。单位随 `unit`（tokens 模式建议调小到 ~30） | `rag/MarkdownHeaderSplitter.java` + `DocumentSplitterFactory.java` | 质量 |
| 9 | **Chunking：标题层级 breadcrumb** | `###` chunk 原本不带父 `##`/`#` 上下文。新增 `breadcrumb` metadata（如 `Top > Sub > SubSub`，仅深度 >1 时注入），`section` 叶子标题保持不变（向后兼容）。检索适配度更高 | `rag/MarkdownHeaderSplitter.java` | 质量 |
| 10 | **摘要异步化** | `SummarizingChatMemory` 压缩的 LLM 调用原在 `add()` 请求路径上同步跑，阻塞响应几百 ms~秒级。改为投后台 daemon 线程池（`app.memory.summary.async`，默认 true）：`add` 只追加立即返回；压缩**快照取在锁内、LLM 调用在锁外、合并回锁内**，期间新 add 的尾部消息保留；单飞防堆叠；LLM 失败不丢消息。代价：bound 变软（压缩完成前可能短暂略超 threshold） | `memory/SummarizingChatMemory.java` + `ChatMemoryConfig.java` | 延迟 |

新增单测：`MarkdownHeaderSplitterTest`（7→12 case：`#`-only 切分 / breadcrumb 路径 / 极小合并 / 大段不合并）+ `SummarizingChatMemoryTest`（4 case：同步压缩 / 异步最终压缩 / 摘要失败不丢消息 / clear）。

验证：`mvn compile` + **104 个单测全过**（95 → +9）。

**仍未做的**（更新后）：记忆 token 计量 + 摘要膨胀上限；A2A input-required / 断连取消；流式后处理；nl2sql 数字 grounding + 自修环轮数上限；tools 错误处理统一。

---

## 已落地优化 2026-06-17（二）：eval 三类黄金集 + baseline CI 门禁

把落地功能（NL2SQL / A2A / workflow）纳入回归网，并加可门禁化的基线对照：

| # | 部分 | 改了什么 | 文件 |
| --- | --- | --- | --- |
| 11 | **harness 扩 3 个 type** | `EvaluationRunner.invokeByType` 加 `sql` / `a2a` / `workflow` 分派：`NlToSqlService` / `A2aService`（走 `message/send` chat skill 同步路径）/ `WorkflowService.start` 三个服务经 `ObjectProvider` 软依赖注入（没开对应 profile 时为 null，跑到该 type 才报清晰错误）。各自把输出归一成 string 喂 Judge（sql: guardBlocked+SQL+行数+解读 / a2a: JSON-RPC response / workflow: status+priority+reply） | `eval/EvaluationRunner.java` + `eval/EvalCase.java` |
| 12 | **3 个黄金集 JSON** | `eval-cases-sql.json`（注入拦截 / 越权表 / 中文枚举聚合 / 租户 top-N，4 case）、`eval-cases-a2a.json`（chat 同步 / 事实题 / 空消息拒绝，3 case）、`eval-cases-workflow.json`（退款进审批 / 高优先级 / 工单抽取，3 case）。`/eval/run?set=sql\|a2a\|workflow` 选集（需先开对应 profile） | `resources/eval/eval-cases-*.json` |
| 13 | **baseline 门禁** | `Baseline`（全局 + per-case 合格线 record）+ `BaselineGate`（纯函数对照，带浮点容差，缺席 case 也判回归）+ `deriveBaseline`（从实测 run 减 slack 生成）。`POST /eval/gate?set=&runs=` 有回归返 **HTTP 422**（CI 据此 fail）、无回归 200；`POST /eval/baseline` 生成基线供落盘提交。`resources/eval/baseline.json` 起步基线 | `eval/Baseline.java` + `eval/BaselineGate.java` + `controller/EvalController.java` |
| 14 | **CI 脚本** | `scripts/eval-gate.sh`：起好应用后 `curl /eval/gate`，解析 `passed` + regressions，按退出码（0 过 / 1 回归 / 2 环境错）供 CI 用 | `scripts/eval-gate.sh` |

新增确定性单测（不连模型）：`BaselineGateTest`（7 case：全局/per-case 门槛、缺席 case、容差、基线生成）+ `GoldenSetsTest`（6 case：4 个黄金集 JSON 结构合法性 + type 一致 + baseline 解析）。

验证：`mvn compile` + **117 个单测全过**（104 → +13）。

> 注意：黄金集 case 的内容（mustInclude 对照值如 `8400` / `赵六` / `WAITING_APPROVAL`）依赖真实 demo 库 + 启用对应 profile 才会真正 pass —— 这是这些功能回归网的「跑法前置」，跟 grounded case 需 auto-ingest 同理。`baseline.json` 是保守起步线，建议首次按真实环境跑 `/eval/baseline?runs=3` 重新生成后提交。

---

## 已落地优化 2026-06-17（三）：A2A / nl2sql / tools / 流式 收尾

把前面识别但未做的剩余项清掉（各带确定性单测）：

| # | 类别 | 改了什么 | 文件 |
| --- | --- | --- | --- |
| 15 | **tools 错误处理统一** | `DateTimeTool` 原 `ZoneId.of`/`daysUntil` 坏入参直接抛异常中断 chat 回合（用户拿 500、模型无反馈）。改成跟 `SqlQueryTool` 一致：返回**可纠错文本**（"Invalid zoneId … use Asia/Shanghai … call again"），让模型下回合自行改写重试。`daysUntil` 返回类型 `long`→`String` | `ai/tools/DateTimeTool.java` |
| 16 | **nl2sql 自修环上限** | `SqlQueryTool` 自修环原无轮数上限，坏 SQL 可反复重试烧 token。加 `app.nl2sql.max-tool-calls`（默 5）：本轮 run_sql 调用数达上限后直接返回终止指令、不再执行 | `nl2sql/SqlQueryTool.java` + `Nl2SqlProperties/Config.java` |
| 17 | **nl2sql 数字 grounding** | 新增 `NumberGrounding`（纯函数）：核对答案里的「数据数字」∈ 查询结果 ∪ 行数 ∪ 问题数字，否则末尾追加 `⚠️ 数字核对提示`（warn 模式，零 LLM）。豁免序数(≤10)/年份压假阳性，归一千分位+小数尾零。`app.nl2sql.number-grounding`（默开） | `nl2sql/NumberGrounding.java` + `NlToSqlService.java` |
| 18 | **流式后处理（PII）** | `/chat/stream` + A2A `message/stream` 原跳过 guardrail。token 已逐个发出无法重写，故做 **append-only 告警**：缓冲完整答案 → `StreamGuard.piiWarningOrNull` 命中则追加 warning 事件。PII 规则抽成共享 `PiiDetector`（`PiiGuardrail` 也复用） | `ai/guardrail/{PiiDetector,StreamGuard}.java` + `ChatController`/`A2aStreamService` |
| 19 | **A2A input-required** | `message/stream` 收口时若回复像澄清式提问（`StreamGuard.looksLikeClarifyingQuestion` 保守启发式），终态置 `INPUT_REQUIRED`（原枚举已预留）而非 `COMPLETED`，给客户端多轮续问语义。`app.a2a.detect-input-required`（默开） | `a2a/A2aStreamService.java` + `A2aProperties.java` |
| 20 | **流式断连取消** | `/chat/stream` + A2A stream 注册 `emitter.onCompletion/onTimeout/onError` → `cancelled` 标记，断开后停止向死 emitter 转发 + 跳过后处理。**限制**：langchain4j 1.13 `TokenStream.start()` 返 void、无取消句柄，<strong>无法真正中止上游 LLM 生成</strong>（仍会跑完），只省转发/后处理开销 | `ChatController.java` + `A2aStreamService.java` |

新增确定性单测：`NumberGroundingTest`(8) + `StreamGuardTest`(6) + `DateTimeToolTest`(4)。

验证：`mvn compile` + **135 个单测全过**（117 → +18）。

**仍未做的**（更新后）：见下「已落地优化 2026-06-17（四）」，多数已清。

---

## 已落地优化 2026-06-17（四）：Doris 批量 + 记忆 token 计量/膨胀上限 + 流式 grounding + A2A 同步 input-required

清掉前面识别的边角项（各带确定性单测）：

| # | 类别 | 改了什么 | 文件 |
| --- | --- | --- | --- |
| 21 | **Doris 批量入库** | `DorisEmbeddingStore.addAll` 原「每个 chunk 一条 INSERT + 一个新 Connection」（N chunk = N 次建连 + N 次往返）。改为**单连接 + 单条多行 INSERT**（`VALUES (...),(...),...`）：向量按数值内联、id/text/metadata 仍走占位防注入。ingestion 快一个量级 | `store/doris/DorisEmbeddingStore.java` |
| 22 | **记忆 token 触发** | `SummarizingChatMemory` 原只按消息条数触发压缩，几条超大消息可在条数没超时撑爆上下文。加 `app.memory.summary.max-tokens`（>0 启用）：估算 token 超预算也触发（与条数取或），`compactNow` 守卫对称放开。0=关、默认零开销 | `memory/SummarizingChatMemory.java` + `ChatMemoryConfig.java` |
| 23 | **摘要膨胀上限** | 多轮「旧摘要+新消息」反复压缩可能越滚越长。加 `app.memory.summary.max-summary-chars`（默 2000）：压出的摘要超长截断兜底（确定性，prompt 的 5-10 bullet 之外加一道） | `memory/SummarizingChatMemory.java` |
| 24 | **流式 grounding** | `/chat/stream` + A2A `message/stream` 原跳过 grounding（ThreadLocal 跨流式回调线程拿不到 source）。改用 `TokenStream.onRetrieved` 捕获检索片段 → 收口时 `GroundingService.streamWarningOrNull`（Layer 0 引用核对 + Layer 1 faithfulness）→ 命中追加 `grounding-warning` 事件/artifact。`inferId` 提为 public static 让两路 id 推导一致 | `ai/grounding/GroundingService.java` + `rag/TaggedSourceContentInjector.java` + `ChatController`/`A2aStreamService` |
| 25 | **A2A 同步 input-required** | 原只在 `message/stream` 接了 input-required；补 `message/send` 同步路径：回复像澄清式提问时返回 `INPUT_REQUIRED` 状态的 `Task`（澄清问题挂 status.message）而非普通 `Message`。复用 `StreamGuard.looksLikeClarifyingQuestion` + `app.a2a.detect-input-required` | `a2a/A2aService.java` |

新增/扩展单测：`SummarizingChatMemoryTest`(+2：token 触发 / 膨胀截断) + `GroundingServiceTest`(+3：流式 Layer 0 引用核对 / 有效引用不报 / 关闭或空直通)。

验证：`mvn compile` + **140 个单测全过**（135 → +5）。

**仍未做的**（边角，按需再说）：流式 grounding 的 Layer 1 会在收口时多一次 temp=0 LLM 调用（延迟尾增，可按 `app.rag.grounding.enabled` 控）；`baseline.json` 仍是保守起步线（建议真实环境 `/eval/baseline?runs=3` 重生成）；多副本下记忆/配额仍是进程内（需 Redis 层，已在各处注释标注演进路径）。

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
| ~~**Chunking 策略优化**~~ | ✅ 完成于 2026-05-27：加 `MarkdownHeaderSplitter`（按 `##` 切节，超长 fallback 到 recursive）+ yml 配置化 `app.rag.chunking.{strategy,max-chars,overlap}`。**实测对本项目结构化 markdown 收益显著**：同一 query 答出 provider 数从 2 → 5（完整召回整个 LLM Provider section）。6 个单元测试。见 docs/qa.md Q8。**2026-06-25 续做四项**：① `strategy=parent-child`（`ParentChildSplitter` small-to-big，child 小块召回 + parent 大块喂 LLM，命中后 injector 换 parent 全文 + 去重）；② `strategy=semantic`（`SemanticChunkingSplitter` 逐句 embed 在 cosine 距离断崖处切，复用主 EmbeddingModel，故障降级 recursive）；③ `app.rag.contextual.*`（Contextual Retrieval，与 strategy 正交，每 chunk 加 temp=0 LLM 上下文前缀再 embed）；④ `ChunkMetrics` 切分质量打点（`rag.chunk.{size,total,tiny,oversize}` 按 strategy tag）。顺带修 `RagIngestionService` 双 split（semantic 双倍 embed 成本）。+20 单测（parent-child 5 / injector 3 / semantic 7 / contextual 5 / metrics 5）。详见 CLAUDE.md「Chunking 策略」节 | ~~中~~ |
| ~~**RAG 事实幻觉事后校验（grounding）warn 模式**~~ | ✅ 完成于 2026-05-28：`app.rag.grounding.*`（默认关）。Layer 0 引用 id 完整性核对（零 LLM）+ Layer 1 `GroundednessChecker` faithfulness（temp=0，RAGAS 拆断言），命中追加 `⚠️ 可信度提示`。挂 `/chat` + `/chat/category`，仅检索到 source 时跑。7 个单元测试 + 2 条 `grounded` eval case。**剩余**：`on-fail=refuse/regenerate`（v1 只 warn）、流式路径、Layer 2 句级 NLU 归因。详见 CLAUDE.md "RAG 事实幻觉事后校验" 节 | ~~中~~ |
| ~~**GraphRAG（图谱增强检索）G1–G4**~~ | ✅ 完成于 2026-06-17：`rag/graph` 包，`app.rag.graph.*`（默认关），零新依赖。补向量召回的「多跳关系 / 实体聚合」盲区——`GraphExtractor`（temp=0）抽三元组建图 + `GraphContentRetriever` N 跳遍历作为**第三路** retriever 并联 router、RRF 融合。三元组带 `sourceId` → `[doc=ID]` 引用 + grounding Layer 0 白嫖。G3：`JdbcGraphStore`（MySQL 边表持久化，代 Neo4j）+ async 后台建图 + 接 kb profile。G4：`entity-linking=llm` + 受限 schema 白名单 + 别名消歧。23 个确定性单测 + 3 条多跳黄金集（`set=graph`）。**剩余**（按信号）：Neo4j 分支、embedding 自动消歧、Global GraphRAG。详见 `docs/graphrag.md` | ~~中~~ |

**做不做的判断条件**：B 看你下一步项目走向 —— 还在钻 prompt + RAG 就做"Re-rank 跑 eval 对比"（最快出价值），符合本项目反复推的"调一处看变化"方法论。GraphRAG（多跳/关系问题盲区）已落 G1+G2。

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
> - **#1 智能客服全闭环** → `docs/workflow-integration.md`。**✅ M1.A 工作流（含上生产硬化 #1–#10）+ M1.B 飞书渠道
>   （意图路由 + 验签解密 + 5s ack + 异步回推 + 审批卡片闭环）均已落地**（飞书出站/卡片回调需真应用联调）。
>
> 两者解耦、各自 `@ConditionalOnProperty` 默认关。客服闭环 #1.A（工作流）→ #1.B（飞书）已按序交付；
> 后续：企微/钉钉/Web/IVR 复制飞书范式 + 飞书多租户 tenant_key 映射。

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

## F. 工作流上生产前待补（Milestone 1.A 后）

Milestone 1.A（Flowable 退款审批）已端到端跑通,但那是 **curl happy path**。下面是审批类长流程
**上线后真正咬人**、当前实现尚未覆盖的 gap,按"会不会真出事故"分三档。**#1–#10 已全部落地（M1.A.1 超时/幂等 + M1.A.2 事务补偿/历史表/大变量/版本化 + M1.A.3 并发审批/回推 outbox/可观测性/PII 删除）**,工作流上生产硬化清零,接飞书渠道前无阻塞。
完整的"触发信号 → 该做什么"明细见 `docs/workflow-integration.md`「上生产前待补的工作流问题」节,此处只做 ROI 总览。

| 档 | 项 | 一句话 | ROI / 触发条件 |
| --- | --- | --- | --- |
| 🔴 一档 | ~~**审批超时 / SLA / 升级**~~ | ✅ 完成于 2026-06-02（M1.A.1）：`ApprovalTimeoutSweeper`（`@Scheduled` 扫挂起超 `app.workflow.approval-timeout` 的 UserTask）→ `WorkflowService.expireTask` 自动驳回 + 审计 `approval.timeout`。**刻意不用 BPMN boundary timer**（那要 `asyncExecutorActivate=true`，会重开坑 2）。日志跨事件串联靠流程变量 `startTraceId` |
| 🔴 一档 | ~~**幂等 / 重复启动**~~ | ✅ 完成于 2026-06-02（M1.A.1）：`start` 加 `dedupeId` → Flowable `businessKey`（`tenant:chatId:dedupeId`）+ start 前查重复用既有实例。无 dedupeId 走随机 UUID（不去重）。残留查-建竞态记了 Redis SETNX 升级点 |
| 🔴 一档 | ~~**`complete` 同步跑 LLM 的事务 / 补偿**~~ | ✅ 完成于 2026-06-02（M1.A.2）：`ServiceTaskDelegates.withRetry` 给 assess/resolve LLM 调用加有界重试 + 降级补偿——**绝不向 Flowable 抛异常**（那会回滚已记录的人工审批决定），耗尽则写降级兜底答复、事务照常提交。事务边界 = 「人工决定 + 一定有终态 reply」原子。延迟仍在（同步等 LLM），彻底去延迟留待渠道阶段异步化 |
| 🟡 二档 | ~~**历史表无限增长**~~ | ✅ 完成于 2026-06-02（M1.A.2）：`WorkflowConfig.setHistory("audit")`（不用 full）+ `WorkflowHistoryCleaner` `@Scheduled` 删超 `app.workflow.history-retention`（默 P30D）已结束历史实例 + `WF_REPLY` 行 |
| 🟡 二档 | ~~**大文本进流程变量**~~ | ✅ 完成于 2026-06-02（M1.A.2）：`reply` 挪到业务表 `WF_REPLY`（`WorkflowReplyStore`，建在 workflow 数据源、写 join 同事务 → 原子 + 持久），同时服务 #3 补偿落点。priority/summary 等短字段仍留流程变量 |
| 🟡 二档 | ~~**流程定义版本化 / in-flight 实例**~~ | ✅ 完成于 2026-06-02（M1.A.2）：续旧版是 Flowable 原生默认；`WorkflowConfig.logVersionTopology` 启动打印各版本在途实例数；策略——微调直接重部署，结构性改动换 `process id`（新 key） |
| 🟢 三档 | ~~**任务分配粒度 + 并发双重审批**~~ | ✅ M1.A.3（2026-06-02）：`claim`/`unclaim` 端点 + assignee；`complete`/`expireTask` 竞态 `FlowableObjectNotFoundException`→**409**（不再 500）；`TaskView` 加 assignee。未做 candidateGroup（assignee 够用） |
| 🟢 三档 | ~~**回推"最后一公里"可靠性**~~ | ✅ M1.A.3：持久化 `WF_OUTBOX` + `WorkflowOutboxDispatcher` @Scheduled 重投（指数退避，4xx/超阈→DEAD DLQ），补 `WebhookDispatcher` 内存重试"进程一挂就丢"的缺口。`start` 传 `webhookUrl` 终态入队，复用 `WebhookSigner`。target 现指 webhook、将来指飞书回调 |
| 🟢 三档 | ~~**工作流可观测性**~~ | ✅ M1.A.3：`WorkflowMetrics` 接 Micrometer——挂起 gauge / 审批耗时 timer / 各终态 counter / 超时 counter，同走 `/actuator/prometheus` |
| 🟢 三档 | ~~**PII 合规删除**~~ | ✅ M1.A.3：`purge(chatId)` 删运行/历史实例 + `WF_REPLY` + `WF_OUTBOX`；`DELETE /workflow/data`（`SCOPE_approve`）；审计 `workflow.data_purged` |

**做不做的判断条件**：这些 gap 的共性是 **curl happy path 测不出来** —— 全是"挂起期间出意外 / 渠道重试 / 跑久了 / 多人并发"才暴露。
**至此 #1–#10 全部落地（M1.A.1/1.A.2/1.A.3）**，工作流上生产硬化清单清零；剩下的工作流增量是渠道阶段的多 pod 调度加锁（outbox `SKIP LOCKED`）、飞书卡片 payload 等，归到渠道里做。

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
| **准备接飞书/任意渠道到工作流** | ✅ F 的 🔴 一档全清：超时 + 幂等（M1.A.1）+ 事务/补偿（M1.A.2）已落地；渠道阶段可直接开工 |
| 审批工单挂起后没人处理、用户干等 | ✅ F 的"审批超时 / SLA / 升级"（M1.A.1 已落地：`ApprovalTimeoutSweeper` 自动驳回） |
| Flowable `ACT_HI_*` 表膨胀 / 查询变慢 | ✅ F 的"历史表无限增长 + 大变量落业务表"（M1.A.2：history=audit + `WorkflowHistoryCleaner` + reply 落 `WF_REPLY`） |
| 多审批人同租户互踩 / 并发 complete 报 500 | ✅ F 的"任务分配粒度 + 并发双重审批"（M1.A.3：claim/unclaim + 竞态 409） |
| 终态回推失败用户干等 / 需可靠投递 | ✅ F 的"回推最后一公里"（M1.A.3：`WF_OUTBOX` 持久 outbox + 重投 + DLQ） |
| 要看工作流挂起数/审批时长/超时率 | ✅ F 的"工作流可观测性"（M1.A.3：`WorkflowMetrics` 接 Micrometer） |
| 个保法"删除我的数据"落到工作流表 | ✅ F 的"PII 合规删除"（M1.A.3：`DELETE /workflow/data` purge） |

没出现的信号就不做。

---

## 关联文档

- 项目历史 → `PROMPT_JOURNEY.md`（看每一轮怎么做的）
- 业务化基线 #1–#7 落地 → `docs/production-hardening.md`
- 工作流接入设计 + 上生产 gap 明细 → `docs/workflow-integration.md`
- 已完成的运营基建 → `docs/observability.md`
- 问答 → `docs/qa.md`
- 项目导航 → `CLAUDE.md`
