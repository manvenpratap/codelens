# REST & WebSocket API Reference

CodeLens exposes a comprehensive, deterministic HTTP REST and streaming WebSocket API. External CI/CD runners, IDE plugins, custom automation scripts, and the frontend web UI interact with the core engine through these contracts.

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
| `POST` | `/api/codestory/generate-pr-story`| Generates PR impact narrative between git refs | `{"base": "main", "head": "feat/x"}` $\to$ Markdown Report |

---

## 🛡️ 4. Structural Integrity & Rules

| Method | Endpoint | Description | Payload / Response |
|---|---|---|---|
| `GET` | `/api/integrity/audit` | Executes or fetches current 14-rule audit | `{"score": 88, "violations": [...], "count": 24}` |
| `GET` | `/api/integrity/rules` | Catalog of all 14 class-awareness rules | `[{"id": "R01", "name": "DTO Boundary", "enabled": true}]` |
| `POST` | `/api/integrity/rules/validate` | Tests custom architecture rule against AST | `{"rule": "CONTROLLER -> REPOSITORY : BLOCK"}` |

---

## 🏛️ 5. Macro Architecture & Metrics

| Method | Endpoint | Description | Query Parameters / Response |
|---|---|---|---|
| `GET` | `/api/macro/modules` | Package coupling ($C_a$, $C_e$) and instability ($I$) | `[{"package": "com.ex.service", "ca": 14, "ce": 6, "i": 0.3}]` |
| `GET` | `/api/macro/martin` | Robert C. Martin metrics ($A, I, D$) per package | `[{"package": "...", "a": 0.4, "i": 0.6, "d": 0.0}]` |
| `GET` | `/api/archetypes` | Classes categorized into 11 archetypes | `{"CONTROLLER": [...], "SERVICE": [...], "DTO": [...]}` |

---

## ⚡ 6. Process Hub & JVM Sentinel

| Method | Endpoint | Description | Payload / Response |
|---|---|---|---|
| `GET` | `/api/process/status` | Real-time JVM memory, GC, threads, CPU load | `{"heapUsedMb": 612, "heapMaxMb": 2048, "threads": 34}` |
| `GET` | `/api/process/engines` | Execution states of all 11 background engines | `[{"engine": "Scanner", "state": "IDLE"}]` |
| `POST` | `/api/process/cache/purge` | Drops soft layout caches and requests proactive GC | `{"freedMb": 184, "currentHeapMb": 428}` |
| `POST` | `/api/process/circuit-breaker/reset` | Resets tripped circuit breakers to `CLOSED` | `{"circuitBreakersReset": 2}` |
| `POST` | `/api/process/stress-test` | Runs 3-second memory/thread stress test | `{"status": "PASSED", "maxConcurrency": 16}` |

---

## 📊 7. Reports Hub APIs

| Method | Endpoint | Description | Supported Formats |
|---|---|---|---|
| `GET` | `/api/reports` | List of all 14 precomputed architecture reports | JSON metadata index |
| `GET` | `/api/reports/{id}` | Fetches analytical payload for a specific report | JSON / Structured Data |
| `GET` | `/api/reports/{id}/export` | Downloads pre-rendered offline report artifact | `?format=html`, `?format=sarif`, `?format=json` |

Supported Report IDs:
`system-architecture`, `package-coupling`, `module-coupling`, `critical-paths`, `structural-integrity`, `dead-code`, `circular-deps`, `complexity-hotspots`, `api-surface`, `data-flow`, `test-coverage`, `security-exposure`, `change-frequency`, `onboarding-guide`.

---

## 🤖 8. Grounded AI Assistant APIs

| Method | Endpoint | Description | Payload / Response |
|---|---|---|---|
| `POST` | `/api/ai/ask` | Natural language question grounded by AST | `{"prompt": "Where is payment processed?"}` $\to$ `{"answer": "...", "sources": [...]}` |
| `POST` | `/api/ai/explain` | Structural breakdown of an entity | `{"entity": "OrderService"}` $\to$ `{"explanation": "...", "role": "SERVICE"}` |
| `POST` | `/api/ai/suggest-refactor` | Refactoring recipe for an integrity violation | `{"violationId": "V-102"}` $\to$ `{"diff": "...", "strategy": "..."}` |

---

## 🔌 9. WebSocket Streaming Telemetry (`/ws/telemetry`)

Clients connect via WebSocket to receive sub-second telemetry streams without HTTP polling overhead:

```json
// Inbound from server every 1000ms:
{
  "type": "TELEMETRY_TICK",
  "timestamp": 1791518200000,
  "jvm": {
    "heapUsedBytes": 641728512,
    "heapMaxBytes": 2147483648,
    "heapPercent": 29.8,
    "liveThreads": 28,
    "gcPauseMsLastMinute": 42
  },
  "circuitBreaker": "CLOSED",
  "activeTasks": 0
}
```
