# Architecture & System Design

CodeLens is designed from the ground up to be **100% offline**, zero-telemetry, and self-contained within a local JVM process. It requires zero cloud services, external databases, or root privileges.


---

## 🏛️ 1. Multi-Module Maven Hierarchy

The system is decoupled into isolated functional layers across 8 Maven modules:

```mermaid
flowchart TD
    App["codelens-app\n(Fat JAR Entry Point & CLI Dispatcher)"] --> API["codelens-api\n(Javalin & Jetty REST, SSE, WS)"]
    API --> Analysis["codelens-analysis\n(CallGraph, CodeStory, AiGroundingService, Reports)"]
    API --> Git["codelens-git\n(JGit Blame, Diffs & PR Stories)"]
    Analysis --> Storage["codelens-storage\n(H2 LZF MVStore & Lucene Search)"]
    Analysis --> Parser["codelens-parser\n(JavaParser AST Visitor & Symbol Extractor)"]
    Storage --> Core["codelens-core\n(Domain Entities & Pure Models)"]
    Parser --> Core
    API --> Web["codelens-web\n(Static Single-Page Application Assets)"]
```

| Module | Purpose & Technologies | Key Components |
|---|---|---|
| `codelens-core` | Pure domain models, zero external dependencies | `CodeType`, `CodeRelationship`, `ScanProgress`, `CodeLensConfig` |
| `codelens-parser` | AST compilation unit analysis using JavaParser 3.25.8 | `AstVisitor`, `JavaSourceScanner`, `SemanticGraphExtractor` |
| `codelens-storage` | Embedded persistent storage with zero administration | `DatabaseManager`, `CodeLensDao`, `EntityDao`, `LuceneService` |
| `codelens-analysis` | Graph topology, CodeStory flows, AI grounding, and audit metrics | `CallGraphAnalyzer`, `StoryEngine`, `AiGroundingService`, `ChangeStoryEngine` |
| `codelens-git` | Version control annotation and churn correlation using JGit | `GitBlameService`, `GitDiffService` |
| `codelens-api` | Asynchronous REST, Server-Sent Events, and WebSockets via Javalin | `CodeLensServer`, `BackgroundTaskOrchestrator`, `HeapAutoRecoveryManager` |
| `codelens-web` | Modern vanilla JS frontend with Canvas, WebGL, and multi-theme styling | Single-page UI, Graphify 2D Canvas, 3D City/Galaxy, Ask CodeStory modal |
| `codelens-app` | Shaded fat JAR packaging and terminal CLI command entry points | `Application` (CLI and daemon launcher) |

---

## 🔄 2. Repository Ingestion & Data Flow

```mermaid
flowchart LR
    subgraph S1["1. Source Code"]
        Java["📄 Java Sources\n(.java AST Walk)"]
    end
    subgraph S2["2. AST Parsing"]
        AST["🔍 JavaParser AST\n(Types, Methods, Fields)"]
    end
    subgraph S3["3. Embedded Store"]
        H2["🗄️ H2 MVStore\n(LZF Compressed)"]
        Lucene["🔎 Lucene Index\n(Symbol Inverted Index)"]
    end
    subgraph S4["4. Analytics Core"]
        Topo["🕸️ JGraphT Call Graph"]
        Story["📖 CodeStory Narratives"]
        Reports["📊 14 Audit Reports"]
    end
    subgraph S5["5. Workspaces"]
        UI["💻 Graphify 2D Canvas"]
        StoryUI["🎬 Storyline Player"]
        AI["🤖 Ask Grounded AI"]
    end

    Java --> AST
    AST --> H2
    AST --> Lucene
    H2 --> Topo
    H2 --> Story
    Lucene --> AI
    Topo --> UI
    Story --> StoryUI
    Reports --> UI
```

---

## 💾 3. Embedded Storage Subsystem

### Embedded H2 Relational Database
* **MVStore with LZF Compression**: Pages are compressed on disk using LZF, minimizing memory usage while delivering sub-millisecond retrieval.
* **Streaming JDBC Cursors**: Ingestion and call-relationship building stream entity FQNs directly through streaming cursors, avoiding large in-memory collections and preventing JVM heap spikes on 125,000+ class repositories.
* **Two-Way DB Mutex**: Heavy operations (e.g. initial scans, database compaction) acquire exclusive DB access via `BackgroundTaskOrchestrator`, preventing lock contention and file corruption.

### Apache Lucene Symbol Search
* **Inverted Token Index**: Every class, method, field, annotation, and package is indexed into an embedded Apache Lucene 9.10 directory.
* **Fuzzy & Wildcard Queries**: Instant sub-5ms typehead symbol search, regex query evaluation, and signature lookups across massive repositories.

---

## 🤖 4. AI Grounding & Semantic Fact Engine (`AiGroundingService`)

CodeLens bridges deep static analysis with AI-powered architectural reasoning while strictly adhering to air-gapped security and truthfulness constraints:

```mermaid
sequenceDiagram
    participant User as Developer (UI / CLI)
    participant API as Javalin Server (:7878)
    participant Ground as AiGroundingService
    participant Storage as Embedded Storage (H2 + Lucene)
    participant Provider as AI Provider (Local / Ollama / OpenAI)

    User->>API: POST /api/ai/ask {"query": "How does order flow work?"}
    API->>Ground: synthesize(query, aiConfig)
    Ground->>Storage: Retrieve Grounded Facts (AST nodes, call edges, sinks)
    Ground->>Provider: Prompt with Strict Fact Constraints & Schema
    Provider-->>Ground: Formatted Narrative + Inferred Relationships
    Ground->>Ground: Verify & Reconcile Citations with AST Proof
    Ground-->>API: Response + Verified Citations Ledger
    API-->>User: Rendered Markdown + Interactive Citation Chips
```

### Multi-Provider Architecture
1. **Deterministic Local Synthesizer (100% Offline)**: Generates structured, verified architectural summaries directly from AST facts, call graph hierarchies, and database sink mappings without requiring any LLM.
2. **Local Ollama Integration**: Connects to locally running LLM engines (e.g. `qwen3-coder:latest`, `llama3`, `gemma4:latest`) at `http://localhost:11434` for private, on-premise AI synthesis.
3. **OpenAI Cloud Integration**: Connects to OpenAI or internal corporate API gateways using configurable models (`gpt-4o-mini`, `gpt-4o`, custom).

### Live Handshake Verification (`POST /api/ai/test`)
Before marking an external provider verified:
* **Ollama**: Performs a live TCP handshake and checks whether Ollama is actively listening on port 11434 and has models available.
* **OpenAI**: Executes a live authenticated API handshake using the provided API key against `GET /v1/models`.
* **Local**: Verifies that the internal static analysis fact engine is initialized.

### Dynamic Model Discovery (`GET /api/ai/models`)
Queries installed models dynamically (e.g. probing Ollama's `/api/tags` endpoint) and automatically populates the model selection dropdown in both Settings and the Ask CodeStory interface.

### Verified Evidence & Citations Ledger
Every answer synthesized by the AI grounding engine includes a **Verified Source Evidence & Citations Ledger**. Readers can click any citation chip to navigate directly to the verified class, method signature, or database mutation sink in the CodeLens explorer.
