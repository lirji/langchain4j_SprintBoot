# Eval Harness 规范

## 1. 总览

评测 harness 用于量化 prompt / 模型 / RAG 配置的改动效果。
入口是 `POST /eval/run?runs=N`，N 默认为 1，推荐 A/B 测试时取 3 或 5。

## 2. Judge 设计

Judge 是一个独立的 AiService（`ai/eval/Judge.java`），
评分时使用专门构造的 ChatModel 实例，固定 temperature=0，
保证「同样的答案多次评分给出同一分数」。

为了避免 Judge LLM 按训练截止日期误判时间相关答案，
EvaluationRunner 会把当前日期 `today = LocalDate.now()` 注入 Judge 的 prompt。

## 3. 客观字段 vs 主观字段

- `coversAllRequiredFacts` 和 `violatesForbidden` 这两个布尔字段由 harness 用
  `String.contains` 规则匹配计算，不让 Judge LLM 判断 —— 避免字面咬文嚼字波动。
- `score` 和 `reasoning` 由 Judge 给出，但被明令禁止重复审 MUST_INCLUDE / MUST_NOT_INCLUDE。
- 通过条件：`coversAllRequiredFacts && !violatesForbidden && score >= 0.6`

## 4. 跨 endpoint 支持

EvalCase 的 `type` 字段决定 dispatch 到哪个服务：

- `chat`（默认）：走 Assistant.chat(...)
- `extract`：走 Extractor.extractTicket(...)，Ticket POJO 序列化成 JSON 喂 Judge
- `multi-agent`：走 MultiAgentService.run(...)，包含 plan 任务数 + finalAnswer
- `reflexive`：走 ReflexiveService.chatReflexive(...)，包含 attempts 数 + finalAnswer

## 5. 并行执行

由 `app.eval.concurrency` 控制（默认 4），通过独立的 `evalExecutor` 线程池实现。
不复用 `multiAgentExecutor` 是为了避免「eval 占用池等 worker，worker 又要从同池拿线程」的死锁。
设 `app.eval.concurrency=1` 退回顺序执行，用于调试。
