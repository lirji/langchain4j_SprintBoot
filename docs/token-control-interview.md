# Token 控制面试速答稿

> 按 **AI Agent 工程师面试**的提问方式组织。每题给「怎么答 + 为什么 + 本项目代码锚点 + 加分点」。
> 配套：prompt/eval 见 `PROMPT_JOURNEY.md`，RAG 面试稿见 `docs/rag-interview-notes.md`，成本/限流落地见 `docs/production-hardening.md`。

## 一句话总览

> 输入 token 靠 **ChatMemory 滑窗 + RAG chunking 计量**控制喂进去多少；输出 token 主要靠 **prompt 约束 + temperature**（没有硬上限，是已知短板）；成本侧靠 **per-tenant 日 token 预算**做请求前 429 闸门 + 调用后回填，再用 Micrometer 把 token 用量打成指标。分四层：输入侧 / 输出侧 / 配额成本 / 可观测。

代码锚点速查：

| 关注点 | 文件 |
| --- | --- |
| ChatMemory 三种滑窗 | `config/ChatMemoryConfig.java` |
| 摘要式记忆 | `memory/SummarizingChatMemory.java` |
| chunking 计量单位（chars/tokens） | `rag/DocumentSplitterFactory.java` |
| 日 token 预算 — 请求前闸门 | `security/TokenBudgetGuardFilter.java` |
| 日 token 预算 — 调用后回填 | `observability/TokenBudgetChatModelListener.java` |
| 日 token 预算 — 状态存储 | `security/TokenBudgetTracker.java` |
| 预算配置（默认/override/anonymous） | `security/TokenBudgetProperties.java` |
| token 用量指标 | `observability/MetricsChatModelListener.java` |
| 预算运维端点 | `observability/TokenBudgetEndpoint.java` |

---

## Q1（基础）「Agent 的 context window 会随多轮对话不断膨胀，你怎么控制？」

讲清三种滑窗策略的取舍，不要只说"截断"：

| 策略 | 实现 | 取舍 |
| --- | --- | --- |
| 按条数 | `MessageWindowChatMemory.withMaxMessages(20)` | 最简单，但**条数 ≠ token**：20 条长消息和 20 条短消息差几倍 token |
| 按 token | `TokenWindowChatMemory.maxTokens(n, estimator)` | 精确控 token 预算，贴合模型 context limit |
| 摘要压缩 | 自实现 `SummarizingChatMemory` | 保留长期事实，但每次压缩多一次 LLM 调用 |

切换：`app.memory.window-mode = messages | tokens | summary`（见 `ChatMemoryConfig`）。

**加分点（摘要式记忆的工程细节，`SummarizingChatMemory.java`）**：

- 超 `threshold` 才触发，**保留最近 `keepRecent` 条 verbatim**，只压更早的 —— 保证模型永远看到最新上下文。
- **增量摘要**：把上一轮的 summary 抽出来（`[Conversation summary so far]` 前缀识别）和新消息一起喂，而不是每次全量重摘 —— 避免摘要漂移、省 token。
- **降级兜底**：摘要 LLM 调用失败时 catch 住，退回纯截断（`return recent`），不让记忆压缩拖垮主链路。
- 构造时校验 `keepRecent < threshold`，否则永远触发不了或一压全压。

> 考点：context 管理是 Agent 的核心成本项，而不是无脑塞全历史。

---

## Q2（进阶）「token 数你怎么算的？本地模型没 tokenizer 怎么办？」

- 用 `OpenAiTokenCountEstimator(gpt-4o-mini)`（tiktoken BPE）做计数，ChatMemory 滑窗和 RAG chunking 共用同一个 estimator（`ChatMemoryConfig` + `DocumentSplitterFactory`）。
- **关键诚实点**：Ollama / bge-m3 这类本地模型**不暴露 tokenizer**，所以用 OpenAI 的 estimator 近似，偏差约 **10–15%**。
- 为什么能接受：滑窗和 chunk 大小都是**软目标**，不是硬边界，10% 偏差不影响正确性；但 ——
- **硬约束场景要小心**：RAG token 模式（`app.rag.chunking.unit=tokens`）下必须保证 `max-size + overlap ≤ embedding 模型 max input`，否则尾部**静默截断**，embedding 质量悄悄下降还不报错。

> 考点：token ≠ 字符数，不同模型 tokenizer 不同。

---

## Q3（成本治理 / 系统设计）「线上 Agent 怎么防止某个租户烧爆账单？」

先点出**限流 ≠ 配额**（`TokenBudgetProperties` 注释里就有这句）：

> 限流（QPS/并发）挡突发，token budget 控成本。一个租户可以 60 QPM 不超 QPS，但每次让 multi-agent 烧 5k token，一天照样爆账单 —— 所以两个维度都要管。

**三组件闭环**（请求前预检 → 调用后回填 → 状态存储）：

1. `TokenBudgetGuardFilter`（请求前）：查今日已用 vs 预算，超了直接 **429 + `Retry-After`**，不浪费 LLM 调用；回写 `X-Token-Budget-Limit/Used/Remaining` 响应头。只拦 LLM-touching family（chat / stream / eval），`/rag/ingest`、`/actuator` 放行。
2. `TokenBudgetChatModelListener.onResponse`（调用后）：从 `TokenUsage` 取 `input+output` 回填；**失败调用不扣**（对齐 SaaS"按成功计费"）。
3. `TokenBudgetTracker`（状态）：`ConcurrentHashMap<tenant, AtomicReference<Usage>>`，日历日自动重置，`updateAndGet` 保证并发原子。

**加分设计细节**：

- **软限制策略**：请求前不知道这次要花多少 token，所以**不预扣**，用"超额即拒、本次仍放行 commit" —— 避免为了精确预扣去预估 token（估不准还增加复杂度）。
- **anonymous 兜底**：未鉴权租户只享 default 的 5%（`anonymousMultiplier`），防止关 auth 时被白嫖烧 token。
- **大客户 override**：`app.token-budget.daily-tokens.overrides.tenantA: 500000` 单独提配额。
- **时区可配**：日历日按 `Asia/Shanghai` 还是 UTC 重置可配，避免随服务器时区漂移。

---

## Q4（陷阱题）「你这个 token 计数器多副本部署还准吗？」

必问追问，主动暴露局限：

- 现在是**进程内 CHM**，单实例准，**多副本下每个实例各算各的，配额会被放大 N 倍**。
- 演进方案（代码注释里已写明）：换 Redis —— `INCRBY tenant:tokens:YYYY-MM-DD` + `EXPIREAT 次日0点`，业务接口（`consume / wouldExceed / currentUsed`）签名不变，只换存储实现。
- 同理 `ChatMemory` 的 in-memory store 多副本也丢，要换 `RedisChatMemoryStore`（项目已实现，`app.memory.store=redis`，按 `chat:mem:<chatId>` 存 JSON + TTL）。

> 主动说"这是 MVP，生产换 Redis"比被问住强很多。

---

## Q5（多 Agent 特有）「Multi-Agent 并行 fan-out，token 成本怎么估？租户怎么归属？」

**成本估算**（`MultiAgentService`）：

- one-shot = Planner(1) + N×Worker(并行) + Synthesizer(1)。
- 开了 replan（`app.multi-agent.replan`）worst case ≈ **2.5× one-shot**（多一轮 Critic + 整轮 DAG + Synthesizer）。所以 replan 默认关，`max-replans=1`。

**租户归属的难点（加分）**：

- 并行 worker 在**子线程**发起 LLM 调用，`TenantContext`（ThreadLocal）默认传不过去。
- 解法：`MdcCopyingTaskDecorator` 把 `TenantContext` + `traceId` 一起透传到 worker 子线程，所以并行调用的 token 也能正确归属租户、日志 traceId 也串得起来。

> 考点：ThreadLocal 跨线程丢失是并行 Agent 必踩的坑。

---

## Q6（输出侧 / 短板）「输出 token 你怎么控？」

诚实 + 有改进思路：

- 当前**没在 model builder 设 `maxTokens` 硬上限**，靠两个软手段：
  - prompt 约束：`app.assistant.tone` 默认"简洁，1–2 句答完"。
  - temperature：主链路 0.7；Judge / Critic / grounding 校验走独立 **temp=0** 的 ChatModel（确定性、可复现）。
- **改进方向（主动说）**：严格控成本时在各 provider builder 补 `maxOutputTokens(...)` 做硬截断；NL2SQL 已经用**强制 LIMIT** 限制返回行数，间接压了"解读"环节喂给 LLM 的输出体积 —— 这是已落地的输出侧间接控制例子。

---

## Q7（可观测）「token 用量怎么监控？」

- `MetricsChatModelListener` 把 `gen_ai.client.token.usage` 打成 Micrometer counter，**按 input/output 拆 tag**，`/actuator/prometheus` 暴露，Grafana 有 token spend panel。
- `TokenBudgetEndpoint` 自定义 Actuator 端点，按 tenant 列今日 used/budget 给运维。

**加分 —— 讲一个真修过的 silent bug**：

> 项目从 LangChain4j starter 自动装配改成 `LlmConfig` 手动建 ChatModel 后，listener 一度没被灌进 builder，metrics 实际**没记录但不报错**。后来改成构造器注入 `List<ChatModelListener>` 灌到每个 chat builder 修好。教训：换装配方式时，依赖 SPI / 自动收集的东西容易静默失效，要有指标兜底验证。

---

## Q8（开放 / roadmap 思维）「成本还能怎么进一步降？」

1. **cost-based 而非 token-based**：`TokenBudgetChatModelListener` 里乘 `model→price` 表，budget 单位从 tokens/day 改 USD/day（注释已规划）—— input/output、不同 model 单价差很多，一视同仁累加不精确。
2. **prompt caching**：系统 prompt / few-shot / RAG 固定上下文走 provider 的 prompt cache，省重复 input token。
3. **小模型路由**：已有 `/chat/auto` 的 LLM-as-router，把简单 query 路由到小模型 / 直接 CHAT 不走 RAG，省检索注入的 token。
4. **检索侧压缩**：rerank 后只注入 top-k 最相关片段，而不是召回多少塞多少。
