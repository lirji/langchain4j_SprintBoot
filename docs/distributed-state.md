# Redis-backed 分布式状态（token 预算样板）

项目里 `TokenBudgetTracker` / `CostTracker` / `RateLimiterRegistry` / `TaskStore` / `SemanticCache` /
workflow outbox 等到处写着「限单 JVM，多副本需 Redis」的注释 —— 但一个都没真做。本次挑最有代表性、且
**多 pod 下是真 correctness bug**（不是理论问题）的 **token 日预算** 落 Redis 后端样板，抽出共享的
`RedisDailyCounters` 范式，并<strong>顺手复用到第二处 `CostTracker`</strong>验证范式可复制 —— 把那一排
「扩展点」从注释变成可验证的现实。

- **token 日预算**：`security` 包，`app.token-budget.store=in-memory|redis`（默认 in-memory）
- **USD 成本累加**：`cost` 包，`app.cost.store=in-memory|redis`（默认 in-memory）

复用已在的 `spring-boot-starter-data-redis`（`RedisChatMemoryStore` 同款依赖），零新依赖。

## 共享范式：`RedisDailyCounters`（`security` 包）

「per-tenant 日计数落 Redis」的可复用纯函数 helper —— 一处定义、两个 tracker 复用（`cost` 已依赖
`security` 的 `TenantContext`，方向不成环）：

| helper | 作用 |
| --- | --- |
| `dayKey(prefix, date, tenant)` | `<prefix><date>:<tenant>`，如 `token:budget:2026-07-04:acme` / `cost:usd:2026-07-04:acme` |
| `scanPrefix(prefix, date)` | `snapshotAll` 的 SCAN 前缀 `<prefix><date>:` |
| `tenantFromKey(fullPrefix, key)` | 从扫描回的 key 切出 tenantId（含 `:` 也不误切；不匹配返回 null） |
| `nextMidnightMillis(clock)` | 配置时区次日 0 点 epoch millis —— key 的 `PEXPIREAT` 绝对过期时刻 |

两个 tracker 的差别只在累加命令：token 用 `INCRBY`（整数），cost 用 `INCRBYFLOAT`（小数）。

## 为什么是"真 bug"而非理论

日 token 预算的进程内实现（`InMemoryTokenBudgetTracker`）每个副本各持一份 CHM 计数。部署 N 个 pod 时，
同一租户的请求被负载均衡打散到各 pod，各算各的 —— **实际配额被放大到 N 倍**，限额形同虚设。这跟"缓存
命中率略低"这种性能问题不同，是限额语义在水平扩容下直接失效。

## 设计

| 关注点 | 做法 |
| --- | --- |
| **接口抽象** | `TokenBudgetTracker` 抽成 interface（保留 `Snapshot` record）。消费方（`TokenBudgetGuardFilter` 预检 / `TokenBudgetChatModelListener` 回填 / `TokenBudgetEndpoint` 快照）只依赖接口，**换后端零改动** —— 兑现 in-memory 版注释当初承诺的"业务接口保持不变" |
| **条件装配** | `SecurityConfig` 两个同类型 `@Bean` 按 `app.token-budget.store` 互斥（`@ConditionalOnProperty`，`in-memory` 默认 `matchIfMissing`），照抄 `app.memory.store` 的 in-memory/redis 范式 |
| **key 内嵌日期** | `<prefix><date>:<tenantId>`（如 `token:budget:2026-07-04:acme`）。date 在前、tenant 在末段 → ① **跨日重置零成本**（新的一天自然换 key、旧 key 到点自动过期，无需定时清理任务）；② `snapshotAll` 能 `SCAN <prefix><today>:*` 枚举、按定长前缀切出 tenantId（tenantId 含 `:` 也不误切） |
| **累加原子** | `consume` 走 Lua（`INCRBY` + `PEXPIREAT` 次日午夜 epoch，一次往返、服务端原子），并发多 pod 不丢更新、不覆盖 |
| **自动过期** | `PEXPIREAT` 设到配置时区的次日 0 点 —— key 到点自动清，Redis 不堆历史 |
| **容错** | Redis 抖动不拖垮主链路：`consume` 失败仅告警（漏记比拒服务代价小）；`currentUsed` 读失败按 0（宁可放行不误拒，硬拦截由 rate-limit 兜底） |
| **一致性口径** | 沿用 in-memory 语义：先 `currentUsed` 预检、请求跑完再 `consume` 回填（**最终一致**，单请求可能轻微越额）。要严格"预扣"可把预检也并进 Lua = 未来项 |

## 怎么跑

```bash
# 起 Redis
docker run -d --name redis -p 6379:6379 redis:7

# 切 redis 后端（spring.data.redis.* 默认指 localhost:6379）；成本累加同理用 --app.cost.store=redis
mvn spring-boot:run -Dspring-boot.run.arguments=--app.token-budget.store=redis

# 打几次 chat 后看 per-tenant 当日 token 快照（多 pod 时是共享计数）
curl -s localhost:8080/actuator/tokenbudget | jq
# Redis 里直接看
redis-cli KEYS 'token:budget:*'      # → token:budget:2026-07-04:anonymous
redis-cli GET  'token:budget:2026-07-04:anonymous'
redis-cli PTTL 'token:budget:2026-07-04:anonymous'   # 到次日午夜的毫秒数
```

多 pod 验证：起两个实例（不同端口）都指同一 Redis，交替打请求，`/actuator/tokenbudget` 两边读到**同一个**
累计值（in-memory 后端则各是各的）。

## 单测（19 case，全不连 Redis）

- `RedisDailyCountersTest`（7 case）：**共享纯函数 helper** —— `dayKey`/`scanPrefix` 布局 / `tenantFromKey`（含 `:` 的 tenantId 不误切、非匹配返回 null）/ `nextMidnightMillis`（按 clock 时区算次日午夜 epoch）。两个 tracker 共用这套，一处测到位。
- `InMemoryTokenBudgetTrackerTest`（7 case）：注入可控 `Clock` 确定性测累加 / **跨日重置** / wouldExceed 阈值 / anonymous 倍率 / snapshot / 非正忽略 / secondsUntilReset。
- `InMemoryCostTrackerTest`（6 case）：累加 / **跨日重置** / snapshot / 非正忽略 / 6 位小数归整 / currency 默认。
- Redis 往返（Lua `INCRBY`/`INCRBYFLOAT` + `PEXPIREAT` / `SCAN`）属集成，靠真 Redis 起服务验证（上面「怎么跑」）。

## 配置

```yaml
app.token-budget:
  store: in-memory        # | redis（多副本共享计数）
  redis:
    key-prefix: "token:budget:"
  timezone: Asia/Shanghai # redis 后端 key 的日期 + PEXPIREAT 均用此时区（cost 也复用此时区）

app.cost:
  store: in-memory        # | redis（多副本成本汇总同一份账）
  redis:
    key-prefix: "cost:usd:"
```

## 可复制到其余"限单 JVM"处

同一范式（接口抽象 + `store` 开关 + `RedisDailyCounters` + Redis 原子命令 + key 内嵌维度自动过期）
已复用到两处，其余候选：

- ~~**`CostTracker`**~~（`INCRBYFLOAT`）—— ✅ 本轮已落地（`app.cost.store=redis`），正是范式可复制的验证
- **`RateLimiterRegistry`** → bucket4j `redis` ProxyManager（pom 注释已指路）
- **`TaskStore` / A2A push store / workflow outbox** → Redis Hash + `SKIP LOCKED` 语义

各处注释里都标了这条演进路径，本样板把"该怎么落"跑通了两遍（token + cost），`RedisDailyCounters` 已是现成的可复用件。
