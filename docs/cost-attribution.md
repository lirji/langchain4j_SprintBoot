# Per-Tenant USD 成本归因

把 token 用量按 model 单价翻成 **USD**，per-tenant 日累加 + Micrometer 指标。补
`app.token-budget.*`（按 token 一视同仁计量、不分模型贵贱）的短板 —— 这条按**钱**核算，
让 `model-cascade`（成本路由）/ `semantic-cache`（0-token 短路）/ Anthropic prompt caching
这几条"降本"叙事第一次有了 $ 口径的收口。

`cost` 包，`app.cost.enabled`（默认关），零新依赖。本地 ollama 免费无需核算，用云 provider
（openai/anthropic/deepseek/gemini）时开。

## 怎么跑

```bash
# 起 openai + 开成本核算
OPENAI_API_KEY=sk-... mvn spring-boot:run \
  -Dspring-boot.run.arguments=--app.llm.provider=openai \
  -Dspring.application.json='{"app":{"cost":{"enabled":true}}}'

# 打几次 chat 后看 per-tenant 当日累计 USD
curl -s localhost:8080/actuator/cost | jq
# → { "anonymous": { "usd": 0.00042, "currency": "USD", "day": "2026-07-04" }, ... }

# Prometheus 里的成本 counter（按 model/provider tag，不带 tenant 避免基数爆炸）
curl -s localhost:8080/actuator/prometheus | grep gen_ai_client_cost_usd
```

## 关键设计

| 关注点 | 做法 |
| --- | --- |
| **定价表** | `CostProperties`（`app.cost.pricing.<model>`）四档单价 **USD / 1M tokens**（云厂商标准口径）：input / output / cache-read / cache-write。model 匹配：精确 → **最长前缀**（`gpt-4o-mini` 命中 `gpt-4o-mini-2024-07-18`）→ `default`（本地免费模型留 0，就不打点/累计） |
| **纯函数换算** | `CostCalculator`（无状态、确定性单测 `CostCalculatorTest`）。**关键是 Anthropic cache 输入拆分**：`AnthropicTokenUsage.inputTokenCount` <em>已含</em> cache-read/cache-write token，三者单价不同（≈0.1× / 1.25× / 1×），所以 `regularInput = input − cacheRead − cacheWrite`（夹 ≥0 防脏数据算负）分别乘价。非 Anthropic 传 0，退化成 input×rate + output×rate |
| **per-tenant 累加** | `CostTracker` 接口 + 两后端（`app.cost.store=in-memory\|redis`）：`InMemoryCostTracker`（日历日重置 + `AtomicReference.updateAndGet` 原子 + CHM 隔离）/ `RedisCostTracker`（`INCRBYFLOAT` 原子累加，多副本汇总同一份账，共用 `security/RedisDailyCounters` 范式，详见 `docs/distributed-state.md`）。只累计不设上限（成本是**可观测**指标，**拦截**仍归 token budget）。时区复用 `app.token-budget.timezone` 同一基准 |
| **接入点** | `CostChatModelListener implements ChatModelListener` → 声明成 Bean 被 `LlmConfig` 的 `List<ChatModelListener>` 构造注入自动灌进每个 chat builder（同 logging/metrics/token-budget listener，**不改 LlmConfig**）。从 `TenantContext` 拿租户（MdcCopyingTaskDecorator 已透传到 multi-agent worker 子线程，归属正确） |
| **指标 vs 明细** | Micrometer `gen_ai.client.cost.usd`（tag `model`/`provider`，**不带 tenant** 防 label 基数爆炸）；per-tenant 明细走 `CostTracker` 内存快照 + `GET /actuator/cost` |
| **默认零回归** | `app.cost.enabled=false` 时 `CostConfig` 整个不装配，listener 不存在、零回调开销 |

## 配置块（application.yml）

```yaml
app.cost:
  enabled: false
  store: in-memory                       # | redis（多副本成本汇总同一份账）
  redis: { key-prefix: "cost:usd:" }
  currency: USD
  default: { input: 0.0, output: 0.0 }   # 未命中 model 兜底（本地免费留 0）
  pricing:                                # USD / 1,000,000 tokens
    gpt-4o-mini:      { input: 0.15,  output: 0.60 }
    gpt-4o:           { input: 2.50,  output: 10.00 }
    claude-haiku-4-5: { input: 1.00,  output: 5.00,  cache-read: 0.10, cache-write: 1.25 }
    deepseek-chat:    { input: 0.27,  output: 1.10 }
```

`/actuator/cost` 已加进 `management.endpoints.web.exposure.include`。

## 单测

- `CostCalculatorTest`（7 case）：input/output 分档乘价 / 未知 model 走 default / **最长前缀匹配** /
  **Anthropic cache 三档拆分** / cache 单价未配回退 input / 脏数据 cache>input 夹到非负 / null tokens 返回 0。
- `InMemoryCostTrackerTest`（6 case）：注入可控 Clock 测累加 / **跨日重置** / snapshot / 非正忽略 / 6 位小数归整 / currency 默认。

## 剩余（按信号）

- ~~**多副本**~~：✅ 已落地 `RedisCostTracker`（`app.cost.store=redis`，2026-07-04，见 `docs/distributed-state.md`）。
- **cost-based 拦截**（USD/day 硬预算，超额 429）：现在只观测不拦。真烧到心疼再把 `CostTracker` 接一个
  guard filter（仿 `TokenBudgetGuardFilter`）—— Redis 后端已就位，直接可做多 pod 硬预算。
- **embedding token 成本**：目前只算 chat model；`/rag/ingest` 的 embedding 调用未计入（与 token-budget 口径一致）。
