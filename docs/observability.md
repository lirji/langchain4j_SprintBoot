# 生产可观测性

## 指标

LangChain4j 的每次 LLM 调用经过 `MetricsChatModelListener`（自实现，因为 `langchain4j-micrometer` 还没发到 Maven Central）记录 4 个 OpenTelemetry GenAI 风格的指标：

| 指标 | 类型 | tags | 含义 |
| --- | --- | --- | --- |
| `gen_ai_client_requests_total` | counter | `provider`, `model` | 调用次数 |
| `gen_ai_client_operation_duration_seconds` | histogram | `provider`, `model` | 端到端调用耗时 |
| `gen_ai_client_token_usage_total` | counter | `provider`, `model`, `type=input\|output` | token 消耗（成本核算用） |
| `gen_ai_client_errors_total` | counter | `provider`, `model`, `error=<ExceptionClass>` | 失败次数按错误类型分类 |

监听器在 `LlmConfig` 启动时通过 `List<ChatModelListener>` 自动灌入每个 `ChatModel` builder。

### 切分质量指标（`ChunkMetrics`）

LLM 指标之外，每次 RAG 入库（`RagIngestionService` 批量 / `DocumentService` 单上传）切分完成后由 `ChunkMetrics` 打点，让「换 chunking 策略 / 调 `max-size` 后切分形态怎么变」可观测，而不是靠人肉看召回 case。所有指标带 `strategy` tag（`recursive` / `markdown-header` / `parent-child` / `semantic`），尺寸统一按**字符数**：

| 指标 | 类型 | tags | 含义 |
| --- | --- | --- | --- |
| `rag_chunk_size` | DistributionSummary | `strategy` | 每个 chunk 的字符长度 → 自动给出 `_count` / `_sum` / `_max`，均值 = sum/count |
| `rag_chunk_total` | counter | `strategy` | 入库 chunk 总数 |
| `rag_chunk_tiny` | counter | `strategy` | 小于 `app.rag.metrics.tiny-chars`（默 50）的碎块/孤儿块数 |
| `rag_chunk_oversize` | counter | `strategy` | 大于 `app.rag.metrics.oversize-chars`（默 2000）的超大块数 |
| `rag_ingest_documents` | counter | `strategy` | 入库文档数 |

`ChunkMetrics` 始终在线（同 `MetricsChatModelListener`，观测能力不藏在开关后），打点成本可忽略；某次入库零 chunk 时只记 `rag_ingest_documents`，碎块/超大块为 0 时不创建对应 counter（保持指标干净）。

样例 PromQL：

```promql
# chunk 平均长度（按策略）
rate(rag_chunk_size_sum[1h]) / rate(rag_chunk_size_count[1h])

# 碎块比例 —— 偏高说明切太碎、单句成块污染检索
sum(rate(rag_chunk_tiny_total[1h])) by (strategy)
  / sum(rate(rag_chunk_total[1h])) by (strategy)

# 超大块比例 —— 偏高说明一块塞多主题、稀释相似度
sum(rate(rag_chunk_oversize_total[1h])) by (strategy)
  / sum(rate(rag_chunk_total[1h])) by (strategy)
```

> 尺寸按字符计量（确定性、零 tokenizer 依赖；作分布监控足够），与 `app.rag.chunking.unit` 的 chars/tokens 解耦——指标只为横向比较切分形态。要 p50/p95 分位，给 `rag_chunk_size` 加 `publishPercentileHistogram` 即可（当前只出 count/sum/max）。

## Prometheus 抓取

应用已暴露 `/actuator/prometheus`。最小 Prometheus 配置：

```yaml
scrape_configs:
  - job_name: langchain4j-demo
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
    static_configs:
      - targets: ["langchain4j-demo:8080"]   # K8s: <svc>.<ns>.svc.cluster.local:8080
```

## Grafana Dashboard

`docs/grafana-dashboard.json` 是预制 dashboard，包含 7 个 panel：

1. Request Rate（req/s）按 provider / model 分组
2. Latency p50 / p95 / p99
3. Token Usage Rate（按 input/output 拆）
4. Error Rate 按异常类型
5. Total Token Spend (24h) ← 成本核算
6. Health 状态
7. Request Count (5m) bar gauge

导入：Grafana → Dashboards → New → Import → Upload JSON file。
依赖一个 `prometheus` 数据源；变量 `$provider` 和 `$model` 自动从指标 label 派生。

## Health Check

`/actuator/health` 已经接入两个自定义 `HealthIndicator`：

- `llm`：对当前 `app.llm.provider` 的 base-url 做 1s TCP 探测
- `embedding`：对当前 `app.embedding.provider` 的 base-url 做 1s TCP 探测

输出示例：

```json
{
  "status": "UP",
  "components": {
    "llm": {
      "status": "UP",
      "details": {"provider": "vllm", "host": "vllm-chat.default.svc.cluster.local",
                  "port": 8000, "tcpConnectMs": 12}
    },
    "embedding": {
      "status": "UP",
      "details": {"provider": "openai-compat", "host": "vllm-embed...", "port": 8000, "tcpConnectMs": 8}
    }
  }
}
```

**TCP 探测的取舍**：只检查网络可达，不发 LLM 请求 → 不烧 token、不需要 api-key 有效、1s 内可结果，适合 K8s readiness/liveness probe。但不反映模型实际可推理能力（那个要靠 `gen_ai_client_errors_total` 监控）。

K8s probe 配置示例：

```yaml
readinessProbe:
  httpGet: {path: /actuator/health/readiness, port: 8080}
  initialDelaySeconds: 5
  periodSeconds: 10
  failureThreshold: 3
livenessProbe:
  httpGet: {path: /actuator/health/liveness, port: 8080}
  initialDelaySeconds: 30
  periodSeconds: 30
```

要让自定义 indicator 进 readiness group，在 yml 配：

```yaml
management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState, llm, embedding
```

## 重试

每个 chat / embedding builder 都接受 `maxRetries`（默认 3），针对 429 限流 / 5xx / 超时自动退避重试。可按 provider 独立配，例如生产 vLLM 跑稳了可降到 1，云 API 保 3：

```yaml
app:
  llm:
    vllm:
      max-retries: 1
    deepseek:
      max-retries: 3
  embedding:
    openai-compat:
      max-retries: 3
```

> 注意：重试是 LangChain4j 客户端内部行为，不会被 `MetricsChatModelListener` 当成多次请求计数 —— `gen_ai_client_requests_total` 反映的是逻辑调用次数，不是物理 HTTP 调用次数。要细分需自己加 HTTP layer interceptor。
