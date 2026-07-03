# Agentic Workflow 模式（Anthropic《Building Effective Agents》全覆盖）

Anthropic《Building Effective Agents》把 LLM 系统分成 **workflow**（预定义代码路径编排 LLM 调用）与 **agent**（LLM 自己决定流程）。本项目把该文提出的 **5 种 workflow 模式 + agent** 全部落地——同一套工程基线（多 provider / 多租户 / 配额 / 审计 / 可观测 / eval）上，从"预定义编排"到"自主"的完整光谱都摆了出来。

> ⚠️ 这里的 "workflow" 指 **LLM 编排模式**，跟本项目 `workflow` 包（Flowable BPMN 业务流程引擎，退款审批）**不是同一个东西**——那是业务流程赛道的词，见 `docs/workflow-integration.md`。

## 5 模式 ↔ 代码

| Anthropic 模式 | 一句话 | 本项目 | 端点 | 开关 |
| --- | --- | --- | --- | --- |
| **Prompt Chaining** | 固定顺序链，每步处理上一步输出，步间插确定性 gate | `ai/chaining` | `POST /chat/chain` | `app.chaining.enabled` |
| **Routing** | 分类 → 分派到专门链路 | `ai/routing`（`QueryClassifier`→RAG/TOOL/CHAT） | `POST /chat/auto` | `app.query-router.enabled` |
| **Parallelization · Sectioning** | 拆**不同**独立子任务并行 | `ai/multiagent`（Planner→DAG 分层并行→Synthesizer） | `POST /chat/multi-agent` | 常开 |
| **Parallelization · Voting** | **同一**任务并行多跑取共识 | `ai/voting`（majority / synthesis） | `POST /chat/vote` | `app.voting.enabled` |
| **Orchestrator-Workers** | 中枢动态拆任务→worker→综合 | `ai/multiagent`（+ replan 闭环） | `POST /chat/multi-agent` | 常开 |
| **Evaluator-Optimizer** | 生成→评估→反馈循环 | `ai/reflexion`（generate→critique→improve） | `POST /chat/reflexive` | 常开 |
| *(Agent，非 workflow)* | LLM 自己决定下一步 | `ai/agent`（开放式 plan→act→observe） | `POST /agent/run` | `app.deep-agent.enabled` |

## Prompt Chaining（`ai/chaining`）

**预定义顺序链 + 步间确定性 gate**。步骤顺序与 gate 写死在配置里，不由模型决定流程，因此可重复、可控、可单测。

- `ChainStep`（`name` + `instruction` + 可选 gate：`gate-min-length` / `gate-must-contain` / `gate-must-match`）
- `ChainLink`（AiService 单节 transform，走主 ChatModel、无记忆）
- `PromptChainService.run(input, steps)`：依次喂过每步，**gate 不过就短路**（返回 `completed=false` + 卡点）
- gate 是这个模式的关键——把跑偏的中间结果拦在早期，别继续喂下去烧后续 token

跑法：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--app.chaining.enabled=true
curl -X POST localhost:8080/chat/chain -H 'Content-Type: application/json' \
  -d '{"input":"LangChain4j 的 RAG 能力"}'
# → {input, steps:[{name,output,gatePassed,gateReason}], finalOutput, completed}
```

默认链（`app.chaining.steps`）是「提纲 → 成文」，成文步带 `gate-min-length:80` 挡空洞输出。改链只动 yml，不动 Java。

## Voting（`ai/voting`）

**同一问题并行跑 N 次 + 聚合取共识**，降低单次随机性、提升可信度。与 Sectioning 互补：Sectioning 并行**不同**子任务，Voting 并行**同一**任务。

- `Voter`（AiService，走主 ChatModel，多样性来自采样温度）
- 两种策略：
  - `majority`（**确定性**多数表决）——归一化（trim+lower）后计票取最高，`agreement=胜出票/总票`，`< min-agreement` 标低置信。离散/分类题用（该不该批准 / 情感极性 / 内容是否违规）
  - `synthesis`（`VoteAggregator` temp=0 LLM 收口）——把 N 票综合成共识答案。自由文本题用
- fan-out 复用 `multiAgentExecutor`

跑法：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--app.voting.enabled=true
curl -X POST localhost:8080/chat/vote -H 'Content-Type: application/json' \
  -d '{"question":"这条评论是否违规？只回答 是/否","n":5}'
# → {question, votes:[...], strategy, decision, agreement, confident}
```

## 单测

- `PromptChainServiceTest`（5）：顺序传递 / min-length 短路 / must-contain 通过 / must-match 失败 / 坏正则不炸链
- `VotingServiceTest`（6）：多数表决 + agreement / 大小写空白归一 / 低于阈值不置信 / synthesis 聚合传入全票 / 无聚合器退化首票 / 显式 n 覆盖配置

都是纯逻辑确定性单测（桩 `ChainLink`/`Voter`，同步 executor），不连模型。

## 为什么值得全铺

这套模式各有适用场景，也**可组合**（Anthropic 原文强调"从简单开始、按需组合"）：路由分派后走链、链的某步内部投票、Orchestrator 的 worker 本身是个 Evaluator-Optimizer……本项目把每种模式做成独立、默认关、可单测的构件，就是为了让它们既能单独演示、也能拼装成更复杂的编排——这正是「带自主深度 Agent 能力的 LLM 应用平台」定位的底座（见 `CAPABILITIES.md` §0 自主度光谱）。
