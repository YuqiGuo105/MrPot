# Mr Pot

Mr Pot is a Spring Boot RAG service that streams "thinking" events while answering questions with retrieval, memory, and file-understanding context.

## Project layout

- **Controllers**: `RagAnswerController` exposes blocking and SSE streaming answer endpoints, and `RagRetrievalController` exposes standalone knowledge-base search endpoints.
- **Services**: `RagAnswerService` orchestrates retrieval, memory, file understanding, prompt assembly, and logging; `RagRetrievalService` performs KB searches with configurable `topK` and `minScore` defaults.
- **Tools**: `KbTools`, `MemoryTools`, and `FileTools` encapsulate KB search, rendered chat history, and file understanding so they can be reused by Spring AI tool-calling or future MCP-style planners.
- **Infra**: `RedisChatMemoryService`, `RagRunLogger`, and `LogIngestionClient` handle chat turn persistence and lightweight analytics logging.

## Request workflow

1. **Retrieval & memory**: KB matches are fetched via `KbTools.search`, and recent chat history is rendered through `MemoryTools.recent` with length caps for prompts.
2. **File understanding**: Attachment URLs are normalized and summarized with `FileTools.understandUrl`, producing text, keywords, and follow-up queries that feed retrieval and prompt context.
3. **Prompt assembly**: `RagAnswerService` builds a system/user prompt containing KB snippets (CTX), file insights (FILE), and chat history (HIS) while enforcing budgets for characters, attachment counts, and QA hints.
4. **Answering**: A `ChatClient` executes the prompt; responses are streamed as SSE `ThinkingEvent` stages (`start`, `redis`, `rag`, `answer_delta`, `answer_final`). Blocking calls append to Redis chat memory after completion.

### Deep thinking mode

`RagAnswerRequest` accepts optional `deepThinking` and `scopeMode` flags. When `deepThinking=true`, the backend runs extra helper steps (roadmap planning, scope guard, entity resolve, privacy sanitize, context compression, verification) and emits additional SSE events before the streamed answer (`roadmap`, `deep_think_mode`, `scope_guard`, `privacy_sanitize`, `evidence`, `entity_resolve`, `context_compress`, `key_info`, `conflict_detect`, `answer_verify`). When omitted or `false`, the request behaves as before. `scopeMode` defaults to `PRIVACY_SAFE` (general questions allowed; privacy requests rejected). Use `YUQI_ONLY` to force Yuqi-only questions.

Example request body:

```json
{
  "question": "他有什么技能",
  "sessionId": "0002424232",
  "deepThinking": true,
  "fileUrls": ["https://.../Yuqi_Guo_Resume.pdf"],
  "scopeMode": "PRIVACY_SAFE"
}
```

## API endpoints

- `POST /api/rag/answer`: blocking RAG answer for a question payload.
- `POST /api/rag/answer/stream`: SSE stream of thinking stages and partial answers.
- `GET /api/rag/retrieve`: simple retrieval with query parameter `q` using default `topK/minScore`.
- `POST /api/rag/retrieve`: advanced retrieval where `topK` and `minScore` can be supplied in the body.

### SSE streaming from the command line

```bash
curl -N --http1.1 -X POST "http://localhost:8080/api/rag/answer/stream" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "question": "他有什么技能",
    "sessionId": "0002424232",
    "deepThinking": true,
    "scopeMode": "PRIVACY_SAFE",
    "fileUrls": ["https://.../Yuqi_Guo_Resume.pdf"]
  }'
```

Swagger UI is available at http://localhost:8080/swagger-ui when the app is running.

## Configuration

- **DashScope (Qwen)**: provide an API key via the environment variable `DASHSCOPE_API_KEY` so Spring AI can initialize the DashScope client and related tooling.
- **Server**: defaults to port `8080`. Adjust standard Spring Boot settings (e.g., via `application.yaml`) as needed for your environment.
- **Code search (optional)**: set `mrpot.code.root` to an absolute repo path to enable the `code_search` tool for deep thinking.

### Local development

```bash
./mvnw spring-boot:run
```

### CI/CD

Expose `DASHSCOPE_API_KEY` (or `SPRING_AI_DASHSCOPE_API_KEY`) as a secret in your pipeline and pass it into the Maven or runtime environment so the DashScope client can start.
