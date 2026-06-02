# Q&A

项目里反复被问到的概念性问题，按时间倒序记录。新问题加到顶部。

每条格式：

- **Q**: 原问题
- **背景**: 为什么问这个 / 误解从哪来
- **A**: 直接答案 + 详细展开 + 取舍

---

## Q10. 为什么大厂一般不让 LLM 判用户意图，而是手动 @Tool 路由？

> 问于 2026-06-02

### 背景

聊到 Flowable 工作流编排时引出的问题。直觉上 LLM 判意图很省事，为什么大厂反而倾向"手动指定 @Tool 来路由"？这里其实藏着一个常见的措辞误区 + 一层工程权衡。

### A

**先纠正措辞**：`@Tool` function-calling 本身并没有绕开 LLM 判意图——模型依然在读 tool 描述、自己决定"该不该调、调哪个"。所以真正的对立面不是"LLM 判意图 vs 手动 @Tool"，而是：

- **自由文本意图分类**：让 LLM 输出 `intent: 退款 / 查询 / 闲聊`，代码再 `switch` 分支走（本项目 `/chat/auto` LLM-as-router 就是这个）
- **受约束的工具调用**：把能做的动作收敛成一组 schema 明确的 `@Tool`，LLM 只能在白名单里选

大厂偏好后者，本质是**把 LLM 的决策空间从"开放问答"压成"枚举选择"**。真正"手动路由"的部分，往往是更外层的规则/关键词粗分发 + 安全门。

#### 为什么压缩这个决策空间

| 维度 | 裸 LLM 判意图 | 受约束 @Tool / 规则路由 |
| --- | --- | --- |
| **确定性** | 同一句话两次可能分到不同 intent，温度、措辞都会抖 | schema 收窄 + 规则路由可 100% 复现 |
| **可测试** | 只能靠 eval harness 看 passRate/σ，是"概率回归" | 普通单测，确定性断言 |
| **可审计** | "为什么走了退款分支"难解释 | 每条分支可记录、可解释，金融/客服合规需要 |
| **延迟+成本** | 每个请求先加一次分类 LLM 调用 | 关键词/规则分发零成本、亚毫秒 |
| **安全** | prompt injection 能操纵意图判断，诱导走危险分支 | 白名单 + 参数 schema 收窄攻击面 |
| **爆炸半径** | 误判直接触发错误业务动作 | 高危动作前还有确定性闸门挡着 |

#### 核心一句话

**越靠近"不可逆的业务动作"（退款、下单、改权限），越不能让一个模糊的 intent classifier 直接触发。** 风险不在于 LLM 偶尔分错——而在于分错之后**直接执行**、没有确定性的拦截层。

所以大厂的真实模式是**分层**，不是"不用 LLM"：

```text
用户输入
  → [确定性粗路由 / 安全门]        ← 规则、关键词、权限校验（不用 LLM）
      → [受约束的 LLM tool-calling]  ← schema 白名单内选工具
          → 高危动作 → [审批工作流 / 人工 review]   ← Flowable 这类
          → 低危动作 → 直接执行
```

LLM 依然在用，但它的判断被包在 **schema + guardrail + 审批** 里，而不是"裸判意图 → 直接动作"。

#### 落到本项目

代码库已经把这套对比写进去了：

- `/chat/auto`（LLM-as-router）= 教学演示"裸判意图"那条路，默认 `app.query-router.enabled=false`，且只在 RAG/TOOL/CHAT 这种**无副作用**的分支间路由——分错了最多答得不好，不会出事故。
- **退款场景**走 `workflow`（Flowable BPMN + 高优先级**人工审批** + MySQL 持久化），不是让 router 判一句"这是退款"就直接退钱。这正是"高危动作必须走确定性工作流 + 人工闸门"的体现。
- NL2SQL 的 **6 层 SQL 护栏**同一哲学：哪怕 LLM 生成了 SQL，执行前还有只读账号/语句白名单/表白名单/强制 LIMIT 兜底——不信任模型的单点判断。

---

## Q9. 切片（chunking）方式都有哪些？怎么选？

> 问于 2026-05-27

### 背景

Q8 答了"chunking 是 RAG 天花板"+ 本项目用了 markdown-header 切。但 chunking 是个大家族，**到底有哪几类策略 + 怎么按场景选**？这条捋一下决策框架，方便后续遇到新文档类型（代码 / PDF / 转录稿）能马上知道该上哪种。

### A

按"**按什么切**"分 3 大类 + 1 个正交维度（检索粒度 vs 返回粒度）。

#### 一、结构无关（不依赖文档语义边界）

| 方式 | 怎么切 | 适用 | 缺点 |
| --- | --- | --- | --- |
| **固定字符数 + overlap** | `recursive(N, M)` 按字符硬切 + M 个 char 重叠 | 任何文档保底 | 句子被切断、跨章节拼接 |
| **token-based** | 按 embedding tokenizer 切 | 想精确控制 embed 输入大小 | 跟字符切本质一样 |
| **Sentence** | 按 `.` `?` `。` 切 | 短文本 / 转录稿 | 单句太短 = chunk 太碎，要二次聚合 |
| **Paragraph** | 按 `\n\n` 切 | 散文 / 文章 / 邮件 | 长段超过 embed 上限就崩 |

#### 二、结构对齐（按文档原本的语义边界切，**推荐起手**）

| 方式 | 怎么切 | 适用 |
| --- | --- | --- |
| **Markdown header**（本项目已做） | 按 `## section` 切，超长 fallback | 技术文档 / FAQ / README |
| **Code-aware** | 按 class / function 切（用 tree-sitter / AST） | 代码库索引 |
| **Layout-aware** | 按 PDF 页面布局 / 表格 / 标题区切 | 扫描件 / 报告（unstructured.io / Docling） |
| **Domain-specific** | 法律按条款、财报按表格、论文按章节+引用块 | 特定领域文档 |

#### 三、语义切（不靠边界靠 embedding 相似度）

| 方式 | 怎么做 | 适用 |
| --- | --- | --- |
| **Semantic chunking** | 先 embed 每个句子，相邻语义相近的合并，相似度跌破阈值切 | 非结构化长文 / OCR / 转录 |
| **Proposition-based** | 用 LLM 把文档拆成原子命题（"X 是 Y"），每命题 1 chunk | 知识抽取场景，能撑得起 LLM 成本 |
| **Late chunking** | 长 context embedding 模型先 embed 整文，再按位置切出 chunk embedding（每 chunk "看到"全局） | 用 Jina v3 / 类似长 context 模型 |

#### 四（正交）：检索粒度 vs 返回粒度可以分开

| 方式 | 思路 |
| --- | --- |
| **Parent-document** | embed 小 chunk（精度高），返回时给 LLM 大 chunk（context 全） |
| **Sentence-window** | 同上但更细：embed 单句，返回 ±N 句作 context |
| **Multi-vector** | 同一 chunk 存多个 embedding（不同摘要 / 不同 aspect 各一份） |

### 设计决策框架（4 个决策）

#### 决策 1：你的文档有没有"原生语义边界"？

- 有（markdown heading / 代码 function / 法律条款）→ **优先按这个边界切**，`recursive` 只当 fallback
- 没有（纯文本 / OCR / chat 转录）→ paragraph / sentence / semantic 三选一

#### 决策 2：chunk 多大？

- 小（100-300 char/token）：精度高 / 检索更准 / 但 context 少
- 中（500-1000）：平衡，**多数场景默认**
- 大（2000+）：context 多 / 但 embedding 精度降 / prompt 长烧钱

实操：**先按语义边界切让 section 长度自然分布，超长 fallback；不要一上来锁死字符数**。

#### 决策 3：要不要 overlap？

- 用了语义边界切 → **通常不用 overlap**（边界本就在"自然停顿"）
- 字符硬切 → 必须 overlap（10-20%），不然跨 chunk 句子被切断
- 上 parent-document → 不用 overlap，parent chunk 天然给足 context

#### 决策 4：检索粒度 = 返回粒度吗？

- 一致（最简单，本项目就是）→ 单一 chunk 既 embed 又给 LLM
- 不一致（parent-document）→ 召回精度 + context 完整度同时拿到，**生产 RAG 推荐**但要写两套存储 / 检索逻辑

### 本项目现状 + 还能怎么推

**当前**：`recursive` + `markdown-header` 两选一。`markdown-header` 对结构化 markdown 显著提升（Q8 实测 40% → 100%）。

**下一步排序**（按 ROI）：

1. **Parent-document retrieval** —— 小 chunk embed + 大 chunk 返回。**ROI 最高**：弥补"chunk 太小 LLM context 不够 / chunk 太大召回不准"的两难。对本项目：可切到 `###` 三级提精度，retrieve 时返回 `##` section 给 context
2. **Code-aware splitter** —— 如果 `documents/` 以后放 Java/Python 文件，要按 class/function 切。Tree-sitter 是标准方案
3. **Sentence-window** —— 对非 markdown 长文（转录稿、客服日志）适用，本项目当前不需要
4. **Semantic chunking** —— fancy 但代价高（先 embed 所有句子）；只在文档完全没结构时考虑
5. **Late chunking** —— 等用 Jina v3 之类长 context embed 模型时再考虑

**避免**：

- Proposition-based 对小项目过度（每 doc 调 LLM 提取命题，成本高 / 不可控）
- Multi-vector 增加存储复杂度，本项目向量库就这么大没必要

### 通用原则

**chunking 是 RAG 链路的天花板**（见 18.17 / Q8）：

- chunk 边界应该跟文档原本的语义边界对齐 —— **markdown 是 heading，代码是 class/function，法律是条款，财报是表格**
- 强行字符数硬切对任何文档都"能跑"，但对每种文档都次优。`recursive` 应该作为 fallback 不是默认
- 检索 / 返回粒度分离（parent-document）是从"切边界对不对"升级到"用什么粒度索引、用什么粒度回答"的下一阶段思考

参考代码：`rag/MarkdownHeaderSplitter.java`、`rag/RagIngestionService.java`（按 yml `strategy` 装配）。

---

## Q8. Chunking 策略到底重不重要？跟 expansion / rerank 比哪个收益大？

> 问于 2026-05-27

### 背景

加了 query-expansion 和 history-aware 之后实测对本项目 corpus 收益不显著（Q6 / Q7）。chunking 策略呢？默认 `recursive(300, 50)` 按字符硬切，能不能换 markdown-header 策略提升召回？

### A

**Chunking 是本项目 RAG 链路里收益最显著的优化** —— 远超 expansion 和 history-aware。原因：本项目文档（`project-faq.md` / `eval-spec.md`）是结构化 markdown，每个 `##` section 是一个完整主题（"支持的 LLM Provider"、"ChatMemory 配置"等）。`recursive(300, 50)` 把 section 切断在字符 300 处，导致一个主题的内容分散在多个 chunk 里，retriever 召回部分信息。

#### 实测对比

同样问题 `本项目支持哪些 chat provider？`：

| 策略 | 召回结果 | 答案完整度 |
| --- | --- | --- |
| `recursive(300, 50)` | 引用 `[doc=project-faq.md#0][doc=project-faq.md#2]` | 只列 **ollama / deepseek** 2 个 provider |
| `markdown-header(max=600)` | 引用整个 "## 支持的 LLM Provider" section | 完整 **5 个 provider**（ollama / openai / anthropic / gemini / deepseek） |

文档原文有 5 个 provider 的完整列表，但 recursive 把它切断了，retriever 只召回到一半。markdown-header 整段保留就完整召回。

#### 收益排序（本项目 corpus）

| 优化 | 实测收益 |
| --- | --- |
| **chunking 改 markdown-header** | **显著**：召回完整度从 40% → 100% |
| query-expansion | 不显著（小 corpus 单 query 已经够好） |
| history-aware | 不显著（小 corpus 改写后召回也不变） |
| rerank（未量化）| 待测 |

这说明对**结构化 markdown 文档**，**chunking 是召回质量的天花板** —— chunk 切错了，后续 expansion / rerank 都救不回（它们提的是"在已有候选里的精度"，不是"补全 chunk"）。

#### markdown-header 的实现要点

```java
public List<TextSegment> split(Document document) {
    // (?m) multiline + lookahead 保留 heading 在 section 开头
    String[] sections = document.text().split("(?m)(?=^##+ )");

    for (String raw : sections) {
        String section = raw.strip();
        if (section.isEmpty()) continue;

        Metadata meta = baseMeta.copy();
        meta.put("index", String.valueOf(idx));
        meta.put("section", extractTitle(section));

        if (section.length() <= maxCharsPerSection) {
            out.add(TextSegment.from(section, meta));
        } else {
            // section 太长 → fallback 到 recursive 在 section 内切，沿用 section metadata
            out.addAll(fallbackForLongSection.split(Document.from(section, meta)));
        }
    }
    return out;
}
```

关键点：

- **regex `(?m)(?=^##+ )`**：`(?m)` 让 `^` 匹配行首，`(?=...)` 正向先行断言保留 `##` 在切分后的 section 开头（不消耗）
- **匹配 `##+`（两个或更多井号）后接空格而非 `#+` 后接空格**：刻意跳过单 `#` 一级标题（通常是文档名/H1，整个文档就一个，不该当分隔点）
- **超长 section fallback 到 recursive**：避免一个 `##` section 巨大（比如 README 那种）成为 1 个 embed 不下的 chunk
- **metadata 注入 `section` 标题**：人工排查检索结果时一眼看到"哦这条来自《支持的 LLM Provider》"，比看 chunk 索引号清楚

#### 什么时候用哪个

| 场景 | 选 |
| --- | --- |
| 结构化 markdown 文档（README / FAQ / 技术规范） | **markdown-header** |
| 任意纯文本 / PDF / Word（无 markdown 结构） | recursive（fallback） |
| 长法律合同 / 论文（按"条款"或"章节"分） | 写一个 `LegalClauseSplitter` 类似的自定义 splitter |
| 代码文件（按 class / function 分） | LangChain4j 有 `DocumentByXxxSplitter` 系列，没合适的就自己写 |

通用模式：**chunk 边界应该跟文档原本的语义边界对齐**。markdown 的语义边界是 heading，代码是 class/function，法律文档是条款。强行用字符数硬切是"无知优化"。

#### 几个延伸方向（roadmap 里没列）

- **Hierarchical / parent-child chunking**：embed 小 chunk（precision），但 retrieve 时返回 parent chunk（更多 context）。LangChain4j 1.13 没内置，要自己实现
- **Semantic chunking**：按 embedding 相似度滑动窗口，相邻句子语义相近的合并。需要先 embed 句子，比基于规则的 chunking 贵很多
- **Multi-modal chunking**：图片 + 文字混排时按 caption / figure 切。本项目不涉及

参考代码：`rag/MarkdownHeaderSplitter.java`、`rag/RagIngestionService.java`（按 yml strategy 装配），`src/test/java/.../MarkdownHeaderSplitterTest.java`（6 个 case）。

---

## Q7. History-aware retrieval 跟 expansion 怎么组合？

> 问于 2026-05-27

### 背景

加了 expansion 之后又加 history-aware，**两个都是 QueryTransformer**，但 LangChain4j 的 `DefaultRetrievalAugmentor` 只接单个 transformer。怎么让两个能同时生效？顺序敏感吗？

### A

自实现一个 `ChainedQueryTransformer`（10 行）把多个 transformer 串成一个：前一个的输出 `Collection<Query>` 逐个喂下一个，结果 flat 起来。

#### 关键：顺序敏感

必须 **compress 先，expand 后**：

```text
原 query "它跟 IoC 啥区别" + history
   ↓ CompressingQueryTransformer (1 LLM call)
1 个 self-contained query "Spring DI 跟 Spring IoC 啥区别"
   ↓ ExpandingQueryTransformer (1 LLM call)
N 个变体: ["DI 和 IoC 概念区别", "Spring 依赖注入 vs 控制反转", "IoC 容器和 DI 的关系"]
   ↓ DefaultQueryRouter
N 路并行检索
   ↓ DefaultContentAggregator (RRF)
top-k 候选
```

颠倒（expand 先 compress 后）就毫无意义 —— expander 看到带代词的原 query 扩出 N 个一样有歧义的变体，compressor 再去对每一个分别拼 history 反而把上下文搞混。

#### 配置组合

```yaml
app:
  rag:
    history-aware:
      enabled: true     # 多轮对话场景必开
    query-expansion:
      enabled: true     # 大 corpus + 模糊 query 才开
      n: 3
    rerank:
      enabled: true     # 召回提升后用 rerank 收口
      type: jina
```

成本是每条 query 多 2-3 次 LLM call（compress + expand + rerank 各 1+N 次）。

#### 实现要点

- **`ChainedQueryTransformer` 自实现 10 行**：把 list of transformers 按序应用，每步 flat。LangChain4j 内部接口 `Collection<Query> transform(Query)` 天然支持 1→N 输出，flat 拼起来即可
- **Bean 注入用 `@Qualifier` + `required=false`**：分别拿 compressing / expanding 两个可选 Bean，按固定顺序组装。两个都 null → 不挂 transformer；只一个 → 直接用单个不包 chain；两个都有 → 包 ChainedQueryTransformer
- **`CompressingQueryTransformer` 不要传 chatMemoryProvider**：它从 `Query.metadata().chatMemoryId()` 自己拿（LangChain4j AiService 拦截时已经注入）。只需要传 chatModel
- **Compressor 和 expander 都用主 ChatModel**：跟 Judge / classifier 不一样，这两个不要 temp=0 —— compress 需要语义理解、expand 需要多样性，主模型 temp=0.7 都合适
- **`@ConditionalOnProperty` 默认关**：跟 query-router 同思路

#### 实测发现：本项目 corpus 收益不显著

| 多轮场景 | baseline T2 | history-aware T2 |
| --- | --- | --- |
| T1 「本项目用什么 ChatMemory 存储？」 | 召回 ChatMemory 段 ✓ | 召回 ChatMemory 段 ✓ |
| T2 「它默认的窗口大小是多少？」 | 「未在文档中找到」 | 「未在文档中找到」 |

History-aware 的 compressor 真跑了（log 多一次 `llm-request messages=1`），但召回结果跟 baseline 一样。**因为本项目小 corpus + nomic-embed-text + 项目文档把"默认窗口"写成"默认上限 20 条消息"，跨概念语义距离大，compressor 改写后的 query 仍命中不了**。

这跟 expansion 那次的发现（Q6）是同一类：**功能挂上 ≠ 召回提升**。要真正受益需要：

- 大 corpus（>1000 文档）
- 真正多轮对话场景（代词指代频繁）
- corpus 内容跟 query 措辞接近

#### 什么时候必须开 history-aware

- 用户在多轮中频繁用代词 / 省略主语（"那个怎么改"、"它支持吗"）
- RAG 索引的文档跟用户 query 用同套术语 —— compressor 改写才有意义
- 业务对召回准确性敏感（如客服系统）

什么时候**不**开：

- 单轮 chat 场景（每个 query 独立）
- corpus 跟 query 措辞完全不同（compressor 改写也救不回）

参考代码：`rag/ChainedQueryTransformer.java`、`config/LangChain4jConfig.java`（`compressingQueryTransformer` Bean + `composeTransformers` 顺序组装）。

---

## Q6. Query expansion 什么时候真有用？

> 问于 2026-05-27

### 背景

`app.rag.query-expansion.enabled=true` 开了之后，原 query 会被 LLM 扩成 n 个变体，多路召回 → RRF 融合。直觉上应该提升召回质量，但实测在本项目 corpus 上**baseline 跟 expansion 召回到一样的 chunk**。什么场景才真有差距？

### A

Expansion 跟 query-router（Q2）的 ROI 故事是同一类 —— **classifier / expander 都是多花 1 次 LLM call 换某种"理论收益"，要看 baseline 已经多好**。

#### 实测两组 case（DeepSeek + Ollama nomic-embed-text + 2 篇 .md，10 segments）

| query | baseline | expansion(n=3) | 差异 |
| --- | --- | --- | --- |
| 「本项目预置的语言模型服务是哪个？」 | `Ollama [doc=#0][doc=#1]` | `Ollama [doc=#0][doc=#1]` | 召回完全一致 |
| 「本系统的 AI 后端默认是什么？」（故意用文档没有的"AI 后端"措辞） | `Ollama [doc=#0]` | `Ollama [doc=#0]` | 召回完全一致 |

**两组都没差**。说明 nomic-embed-text 对"chat provider ↔ 语言模型服务 ↔ AI 后端"这种同义跳跃已经很包容了。

#### 什么场景 expansion 才真有用

| 场景 | 为什么 |
| --- | --- |
| 大 corpus（>1000 文档）相关 chunk 占比低 | 单 query 召回 top-k 可能漏掉真相关的；扩 3 个变体多路召回提升 recall |
| 模糊 / 短 query（"那个 bug"、"配置怎么改"） | LLM 扩展能补充上下文，让 vector 命中更准 |
| 多 sub-question 单 query（"对比 A 和 B 的性能 + 价格"） | expander 拆成 "A 的性能"、"B 的性能"、"A vs B 价格"，分别召回再融合 |
| 跨语言（用户中文问 + 英文文档） | expander 可以补一个英文 query 变体提升召回 |

#### 什么场景不要开

- 小 corpus（<100 文档），embedding 召回已经足够覆盖
- query 已经包含文档原文关键词（"根据文档第 3 节..."）
- 延迟敏感场景 —— expansion 加 ~1-2s LLM call
- 主对话用大模型 + expansion 也用大模型 → 成本翻倍但收益不显著

#### Expansion 跟 rerank 的区别

| 维度 | Expansion | Rerank |
| --- | --- | --- |
| 提升什么 | **召回**（让相关 chunk 进候选池） | **精度**（已召回的候选挑最相关） |
| 多 LLM call | 1 次（expand 时） | N 次（N 个候选各打 1 次分） |
| 跟另一个互斥？ | **不互斥**，叠加效果最好 | 同上 |
| 适合 | 大 corpus / 模糊 query | 召回足够但 noise 多 |

理想的生产 RAG pipeline：**expansion → 多路召回 → 大 candidate-size（如 30）→ rerank top-k（如 5）→ inject 给 LLM**。本项目都支持，按 yml 开关组合即可：

```yaml
app:
  rag:
    query-expansion:
      enabled: true
      n: 3
    rerank:
      enabled: true
      candidate-size: 30
      type: jina  # 云 API 快，需要 JINA_API_KEY
```

#### 关键决策

- **复用 LangChain4j 内置 `ExpandingQueryTransformer`**：不自己写，零代码，配置即用。少踩 prompt 设计的坑
- **classifier ChatModel 用主 ChatModel（非 temp=0）**：跟 Judge / QueryClassifier 不同 —— expander 不要求确定性，反而想要多样性（n=3 应该是 3 个不同变体，不是 3 份相同）。所以用主模型 temp=0.7 OK
- **`@ConditionalOnProperty` 默认关**：跟 query-router 同思路，需要明确权衡才开

参考代码：`config/LangChain4jConfig.java`（`expandingQueryTransformer` Bean + `retrievalAugmentor` 注入逻辑），`application.yml` `app.rag.query-expansion.*`。

---

## Q5. Multi-agent / Reflexive 怎么做 SSE 流式？

> 问于 2026-05-27

### 背景

`/chat/multi-agent` 一次性返回 JSON，但流程是 Plan(~2s) → Workers(并行 ~3-8s/level) → Synthesizer(~10-20s)，**用户感知主要被 Synthesizer 那一截一次性等卡住**。`/chat/reflexive` 同理：每轮 Answerer 几秒 + Critic 几秒，多轮迭代用户等 30s+ 没反馈。

### A

加 SSE 变体 endpoint `/chat/multi-agent/stream` 和 `/chat/reflexive/stream`，按阶段 emit 命名事件。Worker / Critic 这种结构化输出步骤仍非流式（多 worker token 交错难处理；Critic 结构化输出本来就不适合 stream），核心收益在 **Synthesizer 和 Answerer 的最终文本** —— 这两个本身就是大段 free-text，token-by-token 流出来对前端友好。

#### Multi-agent 事件流

```text
event:plan
data:{"tasks":[{"id":"t1","description":"...","dependsOn":[]},{"id":"t2","dependsOn":["t1"]}]}

event:worker-result
data:{"taskId":"t1","description":"...","result":"完整结果"}

event:worker-result
data:{"taskId":"t2","description":"...","result":"完整结果"}

event:synthesis-token
data:HTTP

event:synthesis-token
data: 状态码

... (Synthesizer 流式吐 token)

event:done
data:{"plan":{...},"workerResults":[...],"finalAnswer":"全文"}
```

实测 48 个 synthesis-token + 1 个 done。前端每个 token 立即渲染，最后 done 兜底全文（用于落盘、metrics）。

#### Reflexive 事件流

```text
event:attempt-start
data:1

event:answer-token
data:Spring

event:answer-token
data: Boot

... (Answerer 流式吐 token)

event:critique
data:{"n":1,"answer":"完整 answer","aggregate":0.95,"correctness":0.9,...,"mainIssue":"n/a"}

event:done
data:{"finalAnswer":"...","attempts":[...],"acceptedByThreshold":true}
```

如果 critique 不过阈值，会继续：

```text
event:attempt-start
data:2

event:answer-token   # 这一轮是 improve 不是 answer
...

event:critique
data:{"n":2,...}
```

直到通过或达到 maxAttempts。

#### 实现的几个关键点

- **`Synthesizer` 和 `Answerer` 各加一个 TokenStream 返回的方法**（同 prompt 抽成常量复用，避免在两个方法间复制大段 system prompt）
- **配置层挂 `streamingChatModel`**：`AiServices.builder(...).chatModel(cm).streamingChatModel(scm).build()` —— 缺了就 NullPointerException
- **`Worker` 仍非流式**：多 worker token 交错难处理，且 Worker 输出要完整才能传给下游 task（DAG 依赖）。Worker 完成时直接 emit 整段 `worker-result`
- **`Critic` 仍非流式**：结构化输出（JSON Schema 锁字段）本来就不适合 stream
- **CountDownLatch 把 TokenStream 转阻塞**：reflexive 多轮需要"answer 写完 → 调 critic → 拿到分 → improve"严格顺序，但 TokenStream 本身是 async。用 `CountDownLatch` + `AtomicReference<Throwable>` 包装让上层逻辑保持同步流，不引入 reactive 链路
- **`safeSend` 包 `IOException`**：SSE 客户端可能随时关闭连接，emit 失败不能直接抛 —— 否则 reflexive 的 multi-attempt 流会中途崩溃。捕获后让外层 try/catch 兜底
- **`SseEmitter(180_000L)` timeout**：multi-agent 最坏 30s+，给 3 分钟超时

#### 什么时候用 stream，什么时候用 non-stream？

| 场景 | 用哪个 |
| --- | --- |
| 前端 chat UI（用户在等） | stream，体感关键 |
| 后端服务调用、批处理、eval | non-stream，简单可观测 |
| 想拿到结构化 `plan` / `workerResults` 对象 | stream `done` 事件也含完整对象，或非 stream |
| 真要省总耗时 | 都一样，stream 只省"用户感知延迟"，不省 wall-clock |

参考代码：`ai/multiagent/Synthesizer.java`（双方法）、`ai/multiagent/MultiAgentService.runStream()`、`ai/reflexion/Answerer.java`、`ai/reflexion/ReflexiveService.chatReflexiveStream()`。

---

## Q4. 跨 provider 的 prompt 差异怎么处理？

> 问于 2026-05-26

### 背景

不同 chat provider 对 prompt 的偏好不一样：

- DeepSeek-V3：中文强，但 system prompt 太长会忽略后半段
- Claude Haiku：偏好 XML 标签（`<fact>...</fact>` 之类）
- Gemini Flash：tool-calling 触发不积极，工具描述要更"诱导"
- Ollama 小模型：需要更明确的指令 + few-shot 兜底
- vLLM：看跑哪个 base model

最初版本 `AssistantProperties` 一份默认值用到所有 provider —— 显然不是最优。Round h 加了 per-provider override 机制。

### A

`AssistantProperties` 保留默认字段，新增 `overrides: Map<String, Override>`。启动时按 `app.llm.provider` 解析成 `ResolvedAssistantStyle` Bean，调用方注入 Bean。Override 里任何字段为 null 就 fallback 到默认 —— 部分覆盖，不需要复制整套 default。

#### yml 配置示例

```yaml
app:
  assistant:
    language: "中文"
    tone: "简洁，1–2 句话答完，必要时再展开"
    citation-policy: |-
      引用与来源处理...
    extra: ""
    overrides:
      anthropic:
        # Claude 偏好 XML 标签结构化
        tone: "简洁，1–2 句；分组事实时用 <fact>...</fact> XML 标签"
      gemini:
        # Gemini 触发工具不积极
        extra: "如果有可用工具能直接给答案，立刻调用；不要先猜再决定"
      ollama:
        # Ollama 小模型，每句独立
        tone: "简洁，每句独立成段，避免长复合句；最多 3 句"
      deepseek:
        # DeepSeek 中文强，可以更口语
        tone: "口语化，像跟同事讲技术，必要时用类比；2–3 句话"
```

#### 实测效果

同样问题「什么是 Spring DI？」，DeepSeek 用默认 vs override：

- **默认 tone**：「Spring DI 是 Spring 框架的核心机制，它让对象之间的依赖关系由容器在运行时自动注入...」（2 个正式长句）
- **override 口语 tone**：「**简单说就是**对象不再自己 new 依赖，而是由 Spring 容器在运行时把需要的依赖注入进去。**比如**你有个 Service 需要用到 Dao...」（3 句话 + 代码举例）

#### 设计决策

- **启动时一次性解析 → ResolvedAssistantStyle Bean**：相比每次调用动态查 overrides Map 简单。Provider 切换 = 重启（项目里 provider 本身就是启动期定的）
- **部分覆盖，不是整套替换**：只列要改的字段，其他沿用默认。避免 "改个 tone 还要复制 citationPolicy 一长串"
- **null = fallback，空串 = 真清空**：想真不要 citationPolicy 就传 `citation-policy: ""`；不写或写 `null` 表示沿用上面的默认
- **AssistantProperties 不再被业务调用方用**：4 个调用方（ChatController / CategoryChatService / EvaluationRunner / QueryRouterService）都改成注入 `ResolvedAssistantStyle`。这把"配置长什么样"和"运行时实际用哪份"解耦

#### 什么时候该开 overrides？

**默认不动**。生产里只有 1 个 provider 时完全没必要。要开的信号：

- 真在多个 provider 间路由（少见 —— 大部分项目就一个）
- 某个 provider 上 eval 分数明显偏低，怀疑是 prompt 不匹配
- 切到 Claude 后想用 XML 标签格式，切到 Gemini 想加 tool 诱导

最常用：**临时对照实验**。生产跑 DeepSeek，本地测 Anthropic，加 anthropic override，看是不是 prompt 锁太死。

参考代码：`config/AssistantProperties.java`（默认 + Override 内部类 + resolve 方法）、`config/ResolvedAssistantStyle.java`、`config/AssistantStyleConfig.java`（@Bean 解析）。

---

## Q3. Multi-agent 的 DAG 怎么用？跟 flat 并行比有什么差别？

> 问于 2026-05-26

### 背景

`/chat/multi-agent` 早期是纯并行 fan-out（所有 sub-task 同时跑）。round-h 后加了 DAG 支持，`SubTask` 多出 `dependsOn: List<String>` 字段。**什么场景适合开 DAG**？滥用 DAG 会丧失并行价值。

### A

**默认仍是 flat 并行**（`dependsOn=[]`），只有当一个 sub-task 的指令**字面引用**另一个 sub-task 的输出时才用 DAG。

#### 判断标准

写出 sub-task 的 description 时，如果你必须用类似的措辞才能让 Worker 理解任务：

- 「基于 t1 列出的 X，挑出..」
- 「使用 t1 的结果，进一步...」
- 「根据 t1 给的 3 个候选，选最好的并...」

→ 这就是真依赖，加 `dependsOn: ["t1"]`。

否则别加。即便是逻辑上有顺序关系的任务（比如「先查事实再总结」），如果"总结"完全可以由 `Synthesizer` 合成阶段统一处理 —— 那就让 sub-tasks 平级并行，`Synthesizer` 收尾。**合成是 Synthesizer 的事，不是 Planner 的事**。

#### 实测对比

**Flat case**（HTTP/1.1 vs HTTP/2 三维比较）：

```text
plan.tasks:
  t1 dependsOn=[]: 对比 HTTP/1.1 与 HTTP/2 在连接复用方面的差异
  t2 dependsOn=[]: 对比 HTTP/1.1 与 HTTP/2 在头部压缩方面的差异
  t3 dependsOn=[]: 对比 HTTP/1.1 与 HTTP/2 在多路复用方面的差异

agent-1 / agent-3 / agent-4 同一秒齐开 → 5-7s 内全部完成（并行）
```

**DAG case**（先列 Java 21 特性再聚焦最影响并发的一个）：

```text
plan.tasks:
  t1 dependsOn=[]: 列出 Java 21 引入的 3 个最重要的语言层面新特性
  t2 dependsOn=[t1]: 基于 t1 列出的 3 个特性，挑出对并发编程影响最大的那一个

agent-1 跑 t1（~3s）→ t1 完成后 agent-2 才开始跑 t2（接收 t1 输出作为 upstream context，~9s）
t2 选了 Virtual Threads，详细展开了设计动机 + 代码示例
```

DAG case 里 t2 **不能在不知道 t1 输出**的情况下开始（"挑出"暗示需要选项），所以必须等。

#### 实现要点

- **Kahn 拓扑排序**：按入度分层，同层并行，跨层等待。`MultiAgentService.topologicalLevels()`
- **环检测**：拓扑序中途无法推进 → 有环 → **降级 flat 全并行**（丢掉所有 deps）+ log 警告。不抛异常，因为业务流量可能瞬时 plan 出 bug，丢部分能力比整个失败好
- **上游 id 清洗**：dependsOn 引用不存在的 id 时 log 警告并丢弃，剩下的有效依赖照常执行
- **Worker 不感知 DAG**：只接收 `(task, upstream)` 两参数，upstream 拼好的 string 传过去，Worker 当成普通上下文消化

#### 滥用 DAG 的代价

如果 Planner 把每个 task 都串成链：

```text
t1 → t2 → t3 → t4
```

那就退化成单线程顺序执行，比纯 Synthesizer 合成还慢，**完全失去 multi-agent 价值**。Planner prompt 里专门有一条反例钉这种情况：

> For "对比 X 在 a, b, c 三方面" do NOT chain as:
> `t1: 对比 a`、`t2 [deps: t1]: 对比 b`、`t3 [deps: t2]: 对比 c`。
> Aspects are INDEPENDENT — keep them parallel, no deps.

参考代码：`ai/multiagent/SubTask.java`、`Planner.java`（DAG 教学 + 反例）、`Worker.java`（upstream 参数）、`MultiAgentService.java`（拓扑排序）。

---

## Q2. Query routing (`/chat/auto`) 什么时候值得开？

> 问于 2026-05-26（紧跟 Q1 的后续）

### 背景

Q1 提到"Query routing"是未来可加的 LLM 决策路由，后来真接了。但开它要付出额外的 classifier LLM call 成本，所以不是默认开 —— 什么场景值得权衡这次额外调用？

### A

**默认关。开 `app.query-router.enabled=true` 之前先算账**：classifier 多 1 次 LLM call（500-1500ms + token 成本），换来跳过 RAG 链路（embedding + vector search + 拼检索结果到 prompt + 主模型生成更长 prompt）。

#### 开启 ROI 矩阵

| 场景 | classifier 成本 | RAG 节省 | 净收益 |
| --- | --- | --- | --- |
| 本地 Ollama embedding + 主模型也 Ollama | 1 次本地 LLM call | nomic embed ~50ms + in-memory search <10ms | **亏**（classifier 比 RAG 还贵） |
| 云 embedding（OpenAI / vLLM）+ 主对话用小模型 | 1 次小模型 call | 1 次 embedding API call + 主 prompt 短一截 | **接近持平** |
| 云 embedding + 主对话用大模型 + 大量非 RAG 流量 | 1 次小模型 call（少量 token） | embedding 钱 + 主大模型 prompt 大幅压缩 | **赚** |
| 流量混合不均（80% 都是闲聊，20% 才要 RAG） | 全量加 classify | 80% 流量跳掉 RAG | **赚** |

#### 实测耗时（DeepSeek 跑 3 类 query 各 1 次）

| query 类型 | classifyMs | answerMs | 备注 |
| --- | --- | --- | --- |
| TOOL（现在几点） | 1126 | 1826 | answer 含 tool round-trip |
| RAG（按文档作答） | 729 | 1212 | answer 含 embedding 检索 |
| CHAT（解释概念） | 918 | 1194 | 跳过 RAG |

**总耗时差不多**（~1900-2950ms）—— 在本配置下 classifier 没省到时间。**这正常**，因为：

- DeepSeek API 响应快（700-1200ms/次），classifier 一次几乎等于一次主对话
- 本机 Ollama embedding 也快，RAG 没多大开销

#### 真要省钱时怎么做

- **降级 classifier**：classifier 不必跟主对话同模型 / 同 provider。生产里专门起个 `Qwen2.5-3B-Instruct` / `Llama-3.2-3B-Instruct` 跑 classifier，~200ms 一次，token 量也小
- **缓存 classification**：同一 chatId 短时间内连续问相同主题，路由结果应该一致 → 在 controller 加 5min LRU
- **裁掉 CHAT 路径**：如果业务里几乎所有 query 都该走 RAG，直接关 query-router；如果几乎所有都是 CHAT，直接关 RAG 入库

#### 设计上的几个决策

- **TOOL 和 CHAT 共享 `BareAssistant`**：它跳过 RAG 但保留 tools，所以 TOOL case 走它没问题。区分两档主要是给运维看 metrics 用，不影响路由代码
- **Classifier 用独立 ChatModel + temperature=0**：跟 Judge 同思路，同一 query 多次分类应该给同一答案，否则 routing 会随机分流到不同后端，eval 没法稳定比对
- **`@ConditionalOnProperty` 默认关**：整套 Bean 不构造，关掉时 `/chat/auto` 返回 503 错误，不影响其他 endpoint
- **同一 chatMemoryProvider**：Assistant 和 BareAssistant 共享 chatId 历史，同一会话在两个变种间切换不丢上下文

参考代码：`ai/routing/QueryClassifier.java`、`ai/routing/BareAssistant.java`、`ai/routing/QueryRouterService.java`、`config/QueryRoutingConfig.java`。

---

## Q1. 动态路由是交给 LLM 执行的吗？

> 问于 2026-05-26

### 背景

项目里有很多 "选择" 看上去像决策：用哪个 chat provider、用哪个工具、RAG 召回哪条、multi-agent 拆几个任务等。容易让人以为这些都是 LLM 在自主决定。

### A

**绝大部分路由是代码/配置决定的，LLM 只在 3 个具体地方"决策"** —— 项目刻意把"模型智能"和"基础设施编排"分开。

#### 完整路由地图

| 路由决策 | 谁决策 | 在哪 |
| --- | --- | --- |
| HTTP path → Controller | **Spring MVC** | `ChatController` / `EvalController` 等 |
| Chat provider（ollama/openai/...） | **配置**（启动时定） | `app.llm.provider` → `LlmConfig.switch` |
| Embedding provider | **配置**（启动时定） | `app.embedding.provider` → `EmbeddingModelConfig.switch` |
| 向量库（pgvector/milvus/...） | **配置** | `app.rag.store` → `EmbeddingStoreConfig.@ConditionalOnProperty` |
| Memory store / window mode | **配置** | `app.memory.store` / `window-mode` → `ChatMemoryConfig` |
| 是否调 `@Tool` / 调哪个 | 🤖 **LLM** | tool calling 协议：模型看 `@Tool` 描述自己决定 |
| RAG 多路检索（vector + keyword）| **代码** | `DefaultQueryRouter`，固定 fan-out 两路 |
| RAG re-rank | 🤖 **LLM**（或云 reranker API） | `OllamaLlmScoringModel` / `JinaScoringModel` |
| RAG 类别 filter | **代码**（请求参数） | `?category=xxx` → ThreadLocal → `dynamicFilter` |
| Multi-agent 拆任务 | 🤖 **LLM** | `Planner.@AiService`（结构化输出 `Plan`） |
| Multi-agent 子任务分发 | **代码**（fan-out） | `MultiAgentService` → `CompletableFuture.supplyAsync(executor)` |
| Multi-agent 合成 | 🤖 **LLM** | `Synthesizer.@AiService` |
| Reflexion 是否再迭代 | **代码** + LLM 评分 | `ReflexiveService` `while (agg < threshold)`，agg 来自 `Critic` LLM 评分 |
| Guardrail 是否触发 reprompt | **代码**（regex） | `PiiGuardrail` 正则匹配 PII |
| Eval case → endpoint type | **代码**（switch on `c.type()`）| `EvaluationRunner.invokeByType()` |
| 并发线程分发 | **Java executor** | `multiAgentExecutor` / `evalExecutor` |
| K8s readiness routing | **K8s + Spring Actuator** | `/actuator/health/readiness` |
| **Provider fallback**（A 挂切 B） | ❌ 没做 | 路由层重构，挂在"未做完的"清单 |

#### LLM 决策的 3 个位置（详细）

##### 1. Tool calling（每条 chat 都可能）

- 输入：user message + 所有 `@Tool` 描述
- LLM 决定：要不要调工具 / 调哪一个 / 参数是什么
- 这是项目里 LLM 最频繁的"决策"位
- 代码层完全被动 —— LangChain4j 框架接收 LLM 的 function-call 响应，反射调对应 `@Component` 方法，把结果塞回去再让 LLM 继续

##### 2. Multi-agent Planner（`/chat/multi-agent` 每次）

- 输入：原问题 + Planner system prompt（含 3 例 few-shot + 反例）
- LLM 决定：拆几个子任务 / 每个子任务描述
- 决定后是**死板的 fan-out** —— `MultiAgentService` 不二次决策，所有 sub-task 平等丢线程池
- 没有 DAG 依赖，没有动态加任务

##### 3. RAG re-ranker（当 `app.rag.rerank.enabled=true`）

- 输入：原 query + 一组候选 chunk
- LLM 决定：每个 chunk 跟 query 的相关性打 0-1 分
- 项目里有两种 `ScoringModel` 实现：`OllamaLlmScoringModel`（本地 LLM 当 reranker）和 `JinaScoringModel`（云 API，技术上不是 LLM 是专用 reranker 模型，接口相同）
- 拿到分数后 `ReRankingContentAggregator` 按分排序截 top-k —— 这步又是代码

#### 设计取舍

为什么大部分路由**不**交给 LLM：

- **可预测性**：provider / store / memory mode 这类一旦决定全局生效，没人想要 LLM 半夜决定"我今天想用 PGVector"
- **成本**：每个路由决策都让 LLM 投票 = 每次请求多 N 个 LLM call，token 烧不起
- **可调试**：代码路由可以打日志、能复现；LLM 决策有不确定性，难重现
- **延迟**：tool calling 已经多一轮 round trip，多 agent 决策套娃就秒级响应没了

LLM 做决策的 3 个位置都有**共同特点**：

- 决策本身需要"理解语义"（哪个工具适合这问题 / 怎么拆 / chunk 跟 query 多相关）—— 这是 LLM 强项，代码做不了
- 决策频率低或可控（一次 chat 0-1 个工具决策、一次 multi-agent 1 个拆解决策）
- 决策结果有结构化输出兜底（tool schema / `Plan` record / `0-1` 浮点数），不会失控

#### 未来可能加的 LLM 决策路由

- **DAG planner**：让 Planner 输出带依赖的任务图，工作流按拓扑序执行（现在是无依赖 fan-out）
- **Provider fallback router**：通常用代码（规则：主 provider 错误率 >5% / 1min → 切备）—— LLM 决策反而过度
- **Query routing in RAG**：根据 query 类型决定走 RAG / 走 tool / 走纯 chat。这个适合 LLM 做一次轻量分类（`@AiService classify(query): RouteKind`），结构化输出 enum 即可

参考代码：`config/LlmConfig.java`、`config/EmbeddingModelConfig.java`、`ai/multiagent/MultiAgentService.java`、`ai/reflexion/ReflexiveService.java`、`rag/scoring/OllamaLlmScoringModel.java`。

---

<!-- 后续问题在此之上插入，保持时间倒序 -->
