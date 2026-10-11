# REST & WebSocket API Reference

CodeLens exposes a comprehensive, deterministic HTTP REST, Server-Sent Events (SSE), and streaming WebSocket API. External CI/CD runners, IDE plugins, custom automation scripts, and the frontend web UI interact with the core engine through these contracts.

Base URL: `http://localhost:7878`

---

## 🛰️ 1. Scan Lifecycle & Streaming APIs

| Method | Endpoint | Description | Payload / Response |
|---|---|---|---|
| `POST` | `/api/scan` | Initiates an asynchronous full 8-stage scan | `{"path": "/abs/repo"}` $\to$ `{"scanId": "...", "status": "QUEUED"}` |
| `POST` | `/api/scan/incremental` | Initiates a fast delta scan for modified files | `{"path": "/abs/repo"}` $\to$ `{"status": "SCANNING", "deltaCount": 12}` |
| `GET` | `/api/scan/status` | Current scan stage, percentage, and timing | `{"stage": "CODESTORY", "progress": 94, "elapsedMs": 4210}` |
| `GET` | `/api/scan/events` | **Server-Sent Events (SSE)** stream of stage updates | `text/event-stream` emitting `stage_change`, `progress`, `complete` |
| `GET` | `/api/scan/changes` | Scans disk and returns detected delta manifest | `{"new": [...], "modified": [...], "deleted": [...]}` |
| `POST` | `/api/scan/cancel` | Gracefully terminates any active scanning task | `{"success": true, "message": "Scan cancelled"}` |

---

## 🕸️ 2. Topology & Graph APIs

| Method | Endpoint | Description | Query Parameters / Response |
|---|---|---|---|
| `GET` | `/api/graph` | Full directed call graph with compound clusters | `{"nodes": [...], "edges": [...], "clusters": [...]}` |
| `GET` | `/api/graph/subgraph` | Filtered graph slice by package, class, or depth | `?package=com.example.service&depth=2` |
| `GET` | `/api/graph/call-hierarchy` | Upstream callers or downstream callees BFS | `?class=OrderService&direction=BOTH&hops=3` |
| `GET` | `/api/graph/layout` | Precomputed 2D/3D layout coordinates | `?mode=sunflower` or `?mode=fcose` |
| `GET` | `/api/graph/cycles` | Strongly connected components (dependency loops)| `{"cycles": [["PkgA", "PkgB", "PkgC", "PkgA"]]}` |

---

## 📖 3. CodeStory & Narrative Flows

| Method | Endpoint | Description | Payload / Response |
|---|---|---|---|
| `GET` | `/api/codestory/flows` | Discovered business transaction storylines | `[{"id": "flow_1", "name": "Checkout", "steps": 6}]` |
| `GET` | `/api/codestory/flows/{id}` | Detailed execution step-by-step trace | `{"id": "flow_1", "steps": [...], "sinks": [...]}` |
| `GET` | `/api/codestory/tours` | Precomputed 5-layer guided onboarding tour | `{"modules": [...], "keyFlows": [...], "risks": [...]}` |
| `GET` | `/api/codestory/change-story`| Generates PR impact narrative between git refs | `?base=main&head=feat/x` $\to$ Markdown Summary |
| `POST` | `/api/codestory/what-if` | Simulates blast radius of modifying a symbol | `{"symbol": "com.ex.Service.save"}` $\to$ Impact Report |

---

## 🤖 4. AI Grounding & Handshake APIs

| Method | Endpoint | Description | Payload / Response |
|---|---|---|---|
| `GET` | `/api/ai/config` | Returns active AI provider, model, and status | `{"provider": "ollama", "model": "qwen3-coder:latest", "hasApiKey": false}` |
| `POST` | `/api/ai/config` | Saves AI provider configuration | `{"provider": "ollama", "model": "...", "endpoint": "..."}` |
| `POST` | `/api/ai/test` | Live external handshake test for selected provider | `{"provider": "ollama", ...}` $\to$ `{"ok": true, "message": "Ollama is listening on port 11434"}` |
| `GET` | `/api/ai/models` | Discovers installed models from active provider | `{"models": ["qwen3-coder:latest", "llama3", "gemma4:latest"]}` |
| `POST` | `/api/ai/models` | Probes ad-hoc provider endpoint for model tags | `{"provider": "ollama", "endpoint": "..."}` $\to$ `{"models": [...]}` |
| `POST` | `/api/ai/ask` | Executes architecture-grounded Q&A query | `{"query": "..."}` $\to$ `{"answer": "...", "engine": "...", "citations": [...]}` |

---

## 🛡️ 5. Structural Integrity & Rules

| Method | Endpoint | Description | Payload / Response |
|---|---|---|---|
| `GET` | `/api/integrity/audit` | Executes or fetches current 14-rule audit | `{"score": 88, "violations": [...], "count": 24}` |
| `GET` | `/api/integrity/rules` | Catalog of all 14 class-awareness rules | `[{"id": "R01", "name": "DTO Boundary", "enabled": true}]` |
| `POST` | `/api/integrity/rules/validate` | Tests custom architecture rule against AST | `{"rule": "CONTROLLER -> REPOSITORY : BLOCK"}` |

---

## 🏛️ 6. Macro Architecture & Metrics

| Method | Endpoint | Description | Query Parameters / Response |
|---|---|---|---|
| `GET` | `/api/macro/modules` | Package coupling ($C_a$, $C_e$) and instability ($I$) | `[{"package": "com.ex.service", "ca": 14, "ce": 6, "i": 0.3}]` |
| `GET` | `/api/macro/martin` | Robert C. Martin metrics ($A, I, D$) per package | `[{"package": "...", "a": 0.4, "i": 0.6, "d": 0.0}]` |

---

## ⚙️ 7. Process Hub & System Telemetry

| Method | Endpoint | Description | Payload / Response |
|---|---|---|---|
| `GET` | `/api/tasks` | Live status snapshot across all 18 background tasks | `[{"id": "scanner", "status": "RUNNING", "stage": 1}]` |
| `POST` | `/api/tasks/{id}/cancel`| Cancels an active or queued background task | `{"success": true}` |
| `GET` | `/api/system/threads/dump` | Live thread dump and lock monitor diagnostics | Plain text thread stack trace |
| `GET` | `/api/system/health` | Heap usage, circuit breaker status, and memory | `{"heapPct": 34, "circuitBreaker": "CLOSED"}` |
