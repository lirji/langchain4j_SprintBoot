# 检索质量评测（Recall@k / Precision@k / MRR / Hit@k）

补 `eval-cases.json` 那套 passRate（规则匹配 + LLM Judge）一直**没覆盖的召回层**。这条
**不经 LLM**，只量向量检索器把相关文档捞回来了没 —— 调 chunking / embedding / rerank / min-score 后
重跑，能把「召回变化」和「生成变化」拆开归因。正是 `docs/recall-verification.md` 里反复厘清的
passRate vs 经典 Recall@k 的差别，这次把后者落地成可跑的端点。

`eval/retrieval` 包，零新依赖。

## 两套 eval 的分工

| | `eval-cases.json`（passRate） | `retrieval-cases.json`（本文） |
| --- | --- | --- |
| 测什么 | 答对没（**生成质量**，混检索+LLM 两层） | 相关文档召回没（**纯检索质量**） |
| 经 LLM | 是（Judge 打分） | **否**（只跑 retriever） |
| ground truth | mustInclude/mustNotInclude + judgeHint | 人工标注的 `relevantDocIds` |
| 调 chunking 后 | 分数漂了，但不知是召回还是生成变了 | **直接看 Recall 漂动** |

## 怎么跑

```bash
# 起应用（in-memory 库即可）后：ingest=true 先入库一次，再跑检索评测
curl -s -X POST 'localhost:8080/eval/retrieval?set=default&ingest=true' \
  -H 'X-Api-Key: <带 SCOPE_eval 的 key>' | jq

# 已 ingest 过就不用重复
curl -s -X POST 'localhost:8080/eval/retrieval' -H 'X-Api-Key: ...' | jq
```

返回：

```json
{
  "cases": 8,
  "avgRecall": 1.0, "avgPrecision": 0.42, "meanMrr": 0.83, "hitRate": 1.0,
  "totalDurationMs": 210,
  "results": [
    { "id": "faq-providers", "retrievedIds": ["project-faq.md#1","project-faq.md#0", ...],
      "relevantIds": ["project-faq.md"], "recall": 1.0, "precision": 0.4, "mrr": 1.0, "hit": true }
  ]
}
```

## 关键设计

| 关注点 | 做法 |
| --- | --- |
| **纯函数指标** | `RetrievalMetrics`（确定性单测 `RetrievalMetricsTest`，9 case）：Recall@k = 被召回的相关文档 / 相关总数；Precision@k = 命中相关的召回 / 召回总数；MRR = 第一个相关命中的 rank 倒数；Hit = 是否至少命中一个 |
| **跑真检索器** | `RetrievalEvaluator` 注入主链的 `@Qualifier("vectorRetriever")`（带租户 + category `dynamicFilter`），`retriever.retrieve(Query.from(q))` → 片段 id 用 `TaggedSourceContentInjector.inferId`（与 `[doc=ID]` 引用同源）。量的就是**线上检索器**的真实召回 |
| **用 vectorRetriever 而非整条 augmentor** | 测的是**向量召回本身**（rerank 之前）—— 这正是调 chunking/embedding 时最该看的信号；rerank 是召回**之后**的精排，另算 |
| **id 匹配对切分漂移鲁棒** | 标注分两级：**文件级**（`project-faq.md`，检索 id 的文件部分相等即命中——换 chunk 策略片段号会变但"来自哪个文件"不变，**推荐默认**）/ **精确级**（`project-faq.md#2`，全等才算，钉死具体 section） |
| **前置** | 文档需先入库且 `tenantId` 对得上；`ingest=true` 先跑 `/rag/ingest`（用当前请求线程的 `TenantContext`，与检索侧 filter 同租户） |

## 黄金集（`resources/eval/retrieval-cases.json`）

8 条，靶点是 `documents/` 下三个文件（`project-faq.md` / `eval-spec.md` / `graphrag-demo.md`）。
`set` 选集：`default` → `retrieval-cases.json`；其余 → `retrieval-cases-<set>.json`（集名 `[a-z0-9-]`）。

标注用**文件级**为主（对 chunk 策略鲁棒）。加 case = 往 JSON 加 `{id, question, relevantDocIds}`。

## 剩余（按信号）

- **baseline 门禁**：现在只出报告，未接 `BaselineGate` 那套 CI 阈值对照。真要卡回归可加
  `retrieval-baseline.json`（avgRecall/hitRate 下限）。
- **rerank 后的指标**：目前只测召回层；要量 rerank 精排收益，可再加一条走整条 augmentor 的对照。
- **大规模标注集**：8 条够 smoke，真做 embedding 选型对比建议标到 30-50 条覆盖多语言/模糊 query。
