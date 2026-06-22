# 召回验证与召回率计算

> 配套：`docs/rag-interview-notes.md`（RAG 面试速答稿）、CLAUDE.md「评测 Harness」节。
> 本文聚焦两件事：①「换 chunking 配置后召回掉没掉」怎么用 eval 回归（含 token 模式）；②**召回率到底怎么算**——区分本项目实际算的 `passRate` 和经典 IR 的 `Recall@k`，别在面试里混为一谈。

---

## 1. 召回验证 case：`rag-recall-all-providers`

`src/main/resources/eval/eval-cases.json` 里的一条强召回回归 case，专门验证不同 chunking 配置（尤其 `app.rag.chunking.unit=tokens`）切分后**召回完整性不退化**。

### 1.1 靶点为什么这么选

文档 `documents/project-faq.md` 的 `## 2. 支持的 LLM Provider` 把 **5 个 provider 列在同一个 section** 里：

```
ollama / openai / anthropic / gemini / deepseek
```

这是对 chunking 切分边界**最敏感**的内容：

- 切分**保住整段** → 一个 chunk 含全部 5 个 → 召回完整
- 切分**把 section 切碎**（如 token 模式 `max-size` 设太小）→ 命中 chunk 只含 2~3 个 → 召回不全

所以它是 chunking 切分质量的一个**确定性探针**，不是泛泛的问答题。CLAUDE.md 早记过这个现象：recursive(300) 只召回 2 个 provider，markdown-header(600) 召回完整 5 个。

### 1.2 case 定义

```json
{
  "id": "rag-recall-all-providers",
  "question": "根据文档，本项目支持哪些 chat provider？请完整列出全部可选值，并标注来源。",
  "mustInclude": ["ollama", "openai", "anthropic", "gemini", "deepseek", "[doc="],
  "mustNotInclude": []
}
```

判定逻辑：6 个 `mustInclude` 子串**全部命中**才 pass；任一 provider 名缺失（= 那段没被完整召回）即 fail。`[doc=` 同时守住引用契约。

### 1.3 怎么跑

`app.eval.auto-ingest=true` 会在首次 eval 前**用当前 chunking 配置入库**，所以 `unit` 和 `auto-ingest` 必须**同时在启动设**。

> ⚠️ 坑：多 key 覆盖别用 `-Dspring-boot.run.arguments=--a=x,--b=y`（逗号拼会静默丢第二个，CLAUDE.md 记过）。用 env var（relaxed binding 稳）。

```bash
# token 模式
APP_RAG_CHUNKING_UNIT=tokens APP_EVAL_AUTO_INGEST=true mvn spring-boot:run
# 另开终端
curl -X POST 'localhost:8080/eval/run?runs=3'

# 对照组：chars 模式
APP_RAG_CHUNKING_UNIT=chars APP_EVAL_AUTO_INGEST=true mvn spring-boot:run
curl -X POST 'localhost:8080/eval/run?runs=3'
```

**预期**：两组本 case 都 `passRate=1.0`。token 模式掉了 → `max-size` 太小把 section 切碎了，调大或换 markdown-header 策略。

> 本机注意：8080 若被占用加 `SERVER_PORT=8081`（curl 同步改端口）；需 Ollama 已起且模型已 pull。

---

## 2. 召回率到底怎么算

**关键区分**：面试问「召回率怎么算」，有两套答案，别混。

### 2.1 本项目 eval 实际算的：`passRate`（不是经典召回率）

本项目 harness **没有**算经典 IR 召回率。它算的是 **case 通过率 `passRate`**：

```
passRate = 通过的 run 数 / 总 run 数
```

单个 run 的 pass 条件（`EvaluationRunner`）：

```
coversAllRequiredFacts && !violatesForbidden && score >= 0.6
```

- `coversAllRequiredFacts` —— `mustInclude` 全部子串命中（**规则匹配**，`answer.contains(...)`，不让 Judge 判）
- `violatesForbidden` —— `mustNotInclude` 任一命中
- `score` —— Judge LLM（temp=0）打的 0~1 主观分

→ 这是**端到端答案质量**的代理指标，衡量的是「检索 + 生成」整条链路最终答得对不对，**不是单独的检索召回率**。

`rag-recall-all-providers` 之所以能当「召回探针」，是因为它把 `mustInclude` 设成「**必须被召回才可能写出**的 5 个事实」——召回不全则答案必然漏项 → case fail。**用 pass/fail 间接反映召回完整性**，但它仍是 case 粒度的二元结果，不是 0~1 的召回率数值。

### 2.2 经典 IR 召回率：`Recall@k`

如果面试官要的是**检索层**的召回率，标准定义是 **Recall@k**：

```
                  top-k 检索结果中"相关"的文档数
Recall@k  =  ───────────────────────────────────────
                  该 query 全部"相关"文档总数
```

- 分子：检索器返回的前 k 条里，有多少是真·相关的
- 分母：标注集里这个 query 一共有多少相关文档
- 取值 0~1，越高说明「该召回的没漏」

**worked example**：某 query 标注了 4 个相关文档；检索 top-5 里命中其中 3 个 →

```
Recall@5 = 3 / 4 = 0.75
```

整个测试集的召回率 = 各 query 的 `Recall@k` 取**平均**（macro avg）。

> 配套指标：
> - **Precision@k** = top-k 里相关的占比（分母是 k）——召回率管「漏没漏」，精度管「掺没掺噪声」
> - **MRR** = 第一个相关文档排名倒数的均值——看「正确答案排多靠前」
> - **NDCG@k** = 带位置折扣的相关性增益——最常用的排序质量综合指标
> - **Hit Rate@k** = top-k 是否至少命中 1 个相关文档（二元，最宽松）

### 2.3 算 `Recall@k` 的前提：带标注的黄金集

经典召回率**必须有标注**——每个 query 标好「哪些 doc/chunk id 是相关的」：

```jsonc
// 概念示例：召回评估黄金集
[
  { "query": "本项目支持哪些 chat provider",
    "relevantDocIds": ["project-faq.md#2"] },     // 人工标注的相关 chunk
  { "query": "Judge 用的 temperature",
    "relevantDocIds": ["eval-spec.md#3"] }
]
```

算法：对每个 query 跑检索器拿 top-k 的 doc id 集合，和 `relevantDocIds` 求交集，按上面公式算 `Recall@k`，再对所有 query 平均。

**本项目目前没有这份标注集**，所以走的是 §2.1 的 `passRate` 代理方案——成本低（复用 `mustInclude` 规则匹配），缺点是只能 pass/fail、且把检索和生成耦在一起测。

### 2.4 没有标注时怎么办（本项目的现实选择）

1. **`mustInclude` 强约束 + passRate**（本项目做法）——把「必须被召回的事实」写进 `mustInclude`，用端到端 pass/fail 间接反映召回。便宜、可回归，但非纯检索指标。
2. **LLM-as-Judge 无参考评估**——让 LLM 判「检索到的片段是否覆盖回答所需信息」（RAGAS 的 `context_recall` / `context_precision` 思路）。
3. **faithfulness（本项目已有）**——`GroundednessChecker` 把答案拆原子断言逐条对照 `<source>`，`groundedScore = 被支撑数/总数`。这测的是「答案有没有被检索内容支撑」（忠实度），**与召回率正交**：召回率管「该召回的有没有召回到」，faithfulness 管「答案有没有乱编、是否扣着检索内容说」。

---

## 3. 可能的追问（FAQ）

> 本文每一处「我没做 X」的诚实承认都是面试官的进攻点。下面 5 条按「最可能被问 + 直击弱点」排序，备好能直接背的答案。

### Q1：你没标注集，那怎么低成本造一个？（对应 §2.3「本项目没维护标注集」）

> 用 LLM 反向造：遍历每个 chunk，让 LLM 基于这段生成 1~2 个「只有这段能回答的问题」，`(question → 该 chunk id)` 就是一对弱标注；再人工抽检 10~20% 校正。几百条黄金集一天能造出来，成本远低于纯人工标。RAGAS 的 testset generator 就是这套思路。
>
> 注意：**去重 + 过滤太泛的问题**——答案能在多个 chunk 找到的丢掉，否则相关性标注不干净，算出来的 Recall@k 没意义。

### Q2：passRate 把检索和生成耦在一起，你怎么定位到底是哪层的问题？（对应 §2.1 的弱点）

> 解耦靠看**本轮检索到的 context**：case fail 时先把检索到的 `<source>` 片段打出来（项目里 `RetrievedSourcesContext` 这个 ThreadLocal 正好存了检索到的 id），然后分两种：
> - 相关 chunk **根本没进 context** → **召回层**问题（chunking 切碎 / embedding 不行 / top-k 太小）
> - 相关 chunk **在 context 里但答案还是漏/错** → **生成层**问题（prompt / 模型没利用上）
>
> `rag-recall-all-providers` 故意把靶点设成「召回不全必然答不全」，就是让它的 fail **几乎只可能是召回层**，把变量钉死、避免耦合干扰定位。

### Q3：chunk 切大一点不就全召回了，为什么不？

> 不行，这是召回率和精度的 trade-off。chunk 越大，一个向量要表示的语义越杂 → 向量被「稀释」，query 与它的 cosine 相似度反而下降，**精度掉**；而且大 chunk 喂 LLM 烧 context、带噪声。所以甜区是 256~512 token，既保证语义完整又不稀释。
>
> 真要又全又准，正解是 **小 chunk 检索 + parent-child 召回大块**（检索粒度和喂 LLM 粒度解耦），而不是无脑切大。

### Q4：top-k 怎么定？Recall@k 的 k 选多少？

> k 是召回率与精度/成本的旋钮：k 越大召回率单调不降，但精度掉、喂 LLM 的噪声和 token 成本上升。本项目默认 `app.rag.top-k=5`。定 k 的方法是扫一遍 `Recall@k` 曲线，找「召回率收益开始变平」的拐点。
>
> 如果后面挂 reranker，可以**召回阶段放大 k（如 20）多召，reranker 再收口到 5**——fan-out 提召回、rerank 提精度（项目里 `app.rag.rerank.candidate-size` 就是这个放大数）。

### Q5：线上 query 和你黄金集分布不一样，怎么持续评估？

> 黄金集是离线回归，治不了线上漂移。线上靠两条：
> - **挖 query log**：定期把真实问题（尤其「无召回 / 用户追问重述 / 点了踩」的）抽出来补进黄金集，让测试集跟着真实分布走
> - **线上代理信号**：无召回率、引用点击率、faithfulness 在线打分（项目已有 `GroundednessChecker`）、用户负反馈
>
> 离线黄金集保下限，线上信号抓漂移。

---

## 4. 一句话总结（面试收口）

> 我项目的 eval harness 算的是 **case 通过率 passRate**（`mustInclude` 规则匹配 + temp=0 Judge 打分），是端到端答案质量的代理指标。我加了一条 `rag-recall-all-providers` case，靶点是「5 个 provider 列在同一 section」这种对 chunking 边界最敏感的内容，用 pass/fail **间接回归召回完整性**——切碎了就漏 provider 名、当场 fail，专门守 token 模式切分后召回不退化。
>
> 如果要严格的**检索层召回率**，那是 **Recall@k = top-k 命中的相关文档数 / 全部相关文档数**，需要带标注的黄金集（query→相关 chunk id）。我现在没维护这份标注集，所以用 passRate 代理；要上严格指标，就补标注集 + 配 Precision@k / MRR / NDCG，或用 RAGAS 的 context_recall 做无参考评估。
