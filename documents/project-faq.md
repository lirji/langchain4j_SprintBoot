# LangChain4j Demo 项目 FAQ

## 1. 项目概览

本项目（`langchain4j-demo`）是 LangChain4j + Spring Boot 的脚手架，
当前版本对应 LangChain4j 1.13.1 和 Spring Boot 3.3.5，使用 Java 21。

## 2. 支持的 LLM Provider

本项目当前默认的 chat provider 是 `ollama`（即 `app.llm.provider=ollama`）。
通过 `app.llm.provider` 单一开关切换，可选值如下：

- `ollama`（默认）：本地 Ollama 服务，默认模型 `llama3.1`，需要先 `ollama pull` 模型
- `openai`：OpenAI 官方端点，默认模型 `gpt-4o-mini`，需要环境变量 `OPENAI_API_KEY`
- `anthropic`：Claude，默认模型 `claude-haiku-4-5`，环境变量 `ANTHROPIC_API_KEY`
- `gemini`：Google AI Studio，默认模型 `gemini-2.0-flash`，环境变量 `GOOGLE_AI_GEMINI_API_KEY`
- `deepseek`：走 OpenAI 兼容协议，默认模型 `deepseek-chat`，环境变量 `DEEPSEEK_API_KEY`

不论 chat provider 是哪家，embedding 模型始终使用 Ollama 的 `nomic-embed-text`。

## 3. 支持的向量库

通过 `app.rag.store` 切换：`in-memory`（默认）/ `pgvector` / `milvus` / `chroma` / `qdrant` / `doris`。
其中 Doris 是项目自实现的 `DorisEmbeddingStore`，支持 add / search / remove 以及 metadata filter。

## 4. ChatMemory 配置

- `app.memory.store`：`in-memory`（默认易丢失）或 `redis`（持久化）
- `app.memory.window-mode`：`messages`（按消息数）/ `tokens`（按 token 数）/ `summary`（LLM 摘要压缩）
- 默认上限 20 条消息
