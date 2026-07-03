本项目（langchain4j-demo）是 **一个带自主深度 Agent 能力的企业级 LLM 应用平台/脚手架**，基于 LangChain4j + Spring Boot，当前版本对应 LangChain4j 1.13.1 和 Spring Boot 3.3.5，使用 Java 21。

> **定位澄清**：它不是"一个 Agent"，而是"一个能装下并演示多种自主程度 Agent 的平台"。同一套工程基线上，从**非 Agent 的可控管道**（chat / RAG / NL2SQL 六层护栏 / Flowable 工作流）→ **agentic 模式**（`@Tool` function-calling / Reflexion 自反思 / Multi-Agent DAG 编排）→ **真正自主的 Agent**（`ai/agent` 深度 Agent：开放式 plan→act→observe 循环）一条演进线全铺开。其中 `deep-agent`（`POST /agent/run`）是严格意义上的自主 Agent，NL2SQL / workflow 则是**刻意反 Agent 的可控范式**——两种范式都摆出来，恰是本项目的完整性所在。自主度光谱详见 `CAPABILITIES.md`「项目定位」。

# 文档指南

| 文档 | 内容 |
| --- | --- |
| `CLAUDE.md` | 本仓库给 AI 协作者用的总览（含技术栈/扩展点/注意事项） |
| `docs/scenarios.md` | 业务场景落地总览（知识库问答 / 智能客服，各场景状态与接入入口） |
| `docs/workflow-patterns.md` | Agentic Workflow 模式全覆盖（Anthropic 5 种 workflow + agent ↔ 代码映射） |
| `PROMPT_JOURNEY.md` | Prompt 工程 + eval harness + 生产化的完整演化日志 |
| `docs/roadmap.md` | 待完善项 / ROI 分档 / 决策表 |
| `docs/observability.md` | Prometheus / Grafana / Health Check 接入 |
| `docs/qa.md` | 概念性问答记录（路由 / 决策权 / 设计取舍） |
| `docs/grafana-dashboard.json` | 现成 7-panel dashboard |
| `CAPABILITIES.md` | 本文档：能力清单（参考/checklist） |


# 项目场景

- 引入企业知识库落地场景。（2026-06-02）
- 引入智能客服落地场景（NL2SQL/ChatBI 已落地；工作流编排 + 渠道接入规划中）。（2026-06-02）

> 场景落地总览见 `docs/scenarios.md`。