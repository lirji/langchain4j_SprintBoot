# 语义响应缓存（Semantic Response Cache）

用向量相似度把「意思等价但字面不同」的重复提问归并，命中即返回历史答案、**0 LLM token**，
直接砍掉重复问答的生成成本。默认关（`app.cache.semantic.enabled=false`），零新依赖。

包：`com.lrj.langchain4j.cache.semantic`。

## 解决什么问题

对话流量里存在大量语义等价、字面不同的重复提问：

- 「怎么退款」/「退款流程是啥」/「我要退货怎么弄」
- 「你们几点上班」/「营业时间」/「什么时候营业」

逐字缓存（精确 key match）在这种场景命中率极低。**语义缓存**用 query 向量的 cosine 相似度做近似匹配：
只要新问题与某条历史问题相似度 `>= threshold`（默认 0.95），就判命中、直接复用历史答案，不再触发
检索/生成。

一次 miss 的额外成本只是**一次 embedding 调用**（远比一次 chat completion 便宜）；命中率越高越划算。

## 与其它维度的关系

| 维度 | 关系 |
| --- | --- |
| ChatMemory（会话内多轮） | **正交**。缓存是跨会话/跨用户的「同租户问答复用」，不参与上下文拼接 |
| token-budget（限成本） | **互补**。缓存直接把重复问答的 token 成本降到 0，减轻预算压力 |
| RAG grounding（幻觉校验） | **正交**。命中即短路，不再检索/生成/校验 |

## 工作原理

1. **lookup(query)**：embed query → 在**当前租户**的缓存桶里扫描算 cosine → 取最高分。
   `>= threshold` 即命中（顺手剔除扫到的过期条目），打 `cache.semantic{result=hit}`、返回答案；
   否则打 `result=miss`、返回空。
2. **put(query, answer)**：调用方（`/chat`）跑完模型后回填 `(query 向量 → answer)`。

### 隔离

按 `TenantContext.current()` 的 `tenantId` 分桶，一个租户一份独立缓存——A 租户的答案**绝不会**命中到
B 租户的提问（跟 ChatMemory 的 tenant 前缀隔离同思路）。

### 淘汰

- **容量（LRU）**：每租户桶容量上限 `max-entries`（**每租户**，非全局）。用 access-order
  `LinkedHashMap` 承载——命中/写入即「访问」提升到尾部，超容量时从头部（最久未使用）淘汰。
- **TTL**：单条存活 `ttl`（默认 2 小时）；lookup 时遇到过期条目视为 miss 并清除。答案会随知识库/
  时间漂移，TTL 给一个自然新鲜度上限。`ttl: 0` = 永不过期，只靠 LRU。

### 韧性

embedding 后端故障时 lookup/put 均降级为 no-op（miss / skip），绝不因缓存层拖垮对话主链。

## 配置

```yaml
app:
  cache:
    semantic:
      enabled: false          # 总开关，默认关
      threshold: 0.95         # cosine 命中阈值 [-1,1]，越高越保守（越不易误命中）
      max-entries: 1000       # 每租户缓存条数上限，超出按 LRU 淘汰
      ttl: 2h                 # 单条存活时长；0 = 永不过期（Duration：30m / 2h / 1d）
```

| key | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 总开关。关闭时整条缓存链不装配，`/chat` 行为与历史完全一致 |
| `threshold` | `0.95` | cosine 相似度命中阈值。语义缓存最怕「意思其实不同却误命中返回错答案」，故默认偏保守，宁可 miss |
| `max-entries` | `1000` | 每租户缓存条数上限 |
| `ttl` | `2h` | 单条存活时长，`0` = 永不过期 |

## 装配

- `SemanticCacheProperties` — `@ConfigurationProperties("app.cache.semantic")`
- `SemanticCacheConfig` — `@Configuration` + `@ConditionalOnProperty(app.cache.semantic.enabled=true)` +
  `@EnableConfigurationProperties`
- `SemanticCache` — `@Component`（自身同条件化），依赖已有的 `EmbeddingModel` bean + `MeterRegistry`

`/chat` 经 `ObjectProvider<SemanticCache>` **软依赖**接入：关闭时 `getIfAvailable()==null`，对话链零回归。
接线细节见集成说明（`scratchpad/integration/semantic-cache.md`）。

## 指标

Micrometer counter `cache.semantic{result=hit|miss}`，打在应用共享 `MeterRegistry` 上，
`/actuator/prometheus` 可抓。命中率 PromQL：

```
sum(rate(cache_semantic_total{result="hit"}[5m]))
  / sum(rate(cache_semantic_total[5m]))
```

## 怎么跑

```bash
# 需要一个可用的 embedding 后端（如 Ollama nomic-embed-text）
APP_CACHE_SEMANTIC_ENABLED=true mvn spring-boot:run

# 第一次 = miss，跑模型
curl -s -X POST 'localhost:8080/chat?chatId=u1' -H 'Content-Type: application/json' \
     -d '{"message":"怎么申请退款"}'

# 语义等价的第二次 = hit，返回 "cached":"true"，0 LLM token
curl -s -X POST 'localhost:8080/chat?chatId=u2' -H 'Content-Type: application/json' \
     -d '{"message":"退款流程是什么"}'

curl -s localhost:8080/actuator/prometheus | grep cache_semantic
```

## 单测

`SemanticCacheTest`（7 个确定性单测，桩 EmbeddingModel + 可变 Clock，不连模型/网络）：

- 阈值以上命中 / 阈值以下不命中
- 租户隔离（同一 query 不同租户互不命中）
- LRU 容量淘汰（最近使用的存活，最久未用的被淘汰）
- TTL 过期（超 ttl 后 miss）
- 空白 query 不 embed 直接 miss
- embedding 故障降级为 miss

## 注意 / 取舍

- **只挂 `/chat`（非流式）**：`/chat/stream` 的流式命中需要重新分块推送，收益边际，暂不接。
- **memory-agnostic**：两个用户问同一问题会拿到同一条缓存答案。若答案需要 per-user 个性化，
  权衡后再开；或改为按 `scoped`（tenant+chatId）为 key（命中率会显著下降）。
- **PII / 合规删除**：`SemanticCache#clearCurrentTenant()` 可挂到既有的租户数据清除路径。
- 持久化（Redis 桶）/ 跨副本共享缓存 = 未来项；当前是进程内、重启即丢。
