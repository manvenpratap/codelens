# CodeLens — Project Context & System Architecture

> **Java Codebase Intelligence, AST Architecture & CodeStory Exploration Platform**  
> 100% offline self-contained codebase intelligence tool scanning Java source code to extract AST hierarchies, JGraphT call graphs, field mutation propagation, structural integrity audits, transaction storylines, and 2D/3D visualizers.

---

## 🏛️ System Architecture

Interactive Archify HTML: [`docs/diagrams/architecture.html`](./docs/diagrams/architecture.html)  
Specification: [`docs/diagrams/architecture.json`](./docs/diagrams/architecture.json)

```mermaid
flowchart TD
    User["👤 Developer / Architect\n(Verlet Graphify / 3D WebGL / Storyline Player)"]
    
    subgraph JVMBoundary["☕ JVM Fat-JAR Runtime (Zero Cloud Dependencies)"]
        API["⚡ Javalin Server\n(Embedded Jetty REST / WS / SSE :7878)"]
        
        Parser["🔍 AST Ingestion Core\n(JavaParser 3.25.8 Worker Pool)"]
        Analyzer["🕸️ Topology Engine\n(JGraphT Directed Call Graph & DSM)"]
        Integrity["🛡️ Structural Integrity\n(14 Active Class Rules & AST Drift)"]
        CodeStory["📖 CodeStory Engine\n(Transaction Storylines & Guided Tour)"]
        Reports["📊 Reports Engine\n(14 Precomputed Architecture/Risk Reports)"]
        ProcessHub["⚙️ Process Hub & Sentinel\n(11 Engines, Watchdog & Circuit Breaker)"]
        Git["📜 Git Churn Engine\n(JGit Blame, PR Story & Heatmaps)"]
        
        subgraph StorageLayer["💾 Storage Subsystem"]
            H2[("🗄️ H2 Database\n(LZF MVStore Compressed Tables)")]
            Lucene[("🔎 Lucene Search\n(Full-Text Code & Symbol Index)")]
        end
    end

    User -->|"REST / WS / SSE"| API
    API -->|"1. Parse AST"| Parser
    API -->|"3. Topology"| Analyzer
    API -->|"6. Integrity"| Integrity
    API -->|"7. CodeStory"| CodeStory
    API -->|"8. Reports"| Reports
    API -->|"Process Telemetry"| ProcessHub
    API -->|"Git Blame / PR Story"| Git
    Parser -->|"batch insert"| H2
    Parser -->|"tokenize symbols"| Lucene
    Analyzer -->|"read entities"| H2
    Integrity -->|"audit schema"| H2
    CodeStory -->|"trace sinks"| H2
    Reports -->|"precompute"| H2
```

---

## 🔄 8-Stage Ingestion & Analysis Workflow

Interactive Archify HTML: [`docs/diagrams/workflow.html`](./docs/diagrams/workflow.html)  
Specification: [`docs/diagrams/workflow.json`](./docs/diagrams/workflow.json)

```mermaid
flowchart LR
    A["Scan Trigger\n(CLI / Web UI)"] --> B["1. Parse AST\n(JavaParser walk)"]
    B --> C["2. Index\n(Lucene & H2)"]
    C --> D["3. Topology\n(JGraphT call graph)"]
    D --> E["4. Layouts\n(Sunflower 2D/3D)"]
    E --> F["5. Modules\n(Coupling & stability)"]
    F --> G["6. Integrity\n(14 class rules)"]
    G --> H["7. CodeStory\n(Storylines & tour)"]
    H --> I["8. Reports\n(14 precomputed)"]
    I --> J["Complete & Ready\n(100% features live)"]
```

---

## ⚡ 8-Stage Scan & CodeStory Execution Sequence

Interactive Archify HTML: [`docs/diagrams/sequence.html`](./docs/diagrams/sequence.html)  
Specification: [`docs/diagrams/sequence.json`](./docs/diagrams/sequence.json)

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Developer
    participant API as Javalin API (:7878)
    participant Parser as JavaParser Worker
    participant DB as Storage Core (H2/Lucene)
    participant Graph as Topology Engine (JGraphT)
    participant Story as CodeStory Engine
    participant UI as Workspace UI

    Dev->>API: POST /api/scan
    API->>Parser: Start 8-Stage Sequential Walk
    activate Parser
    Parser->>DB: Stream Entity Chunks (Stage 1: PARSE)
    activate DB
    DB-->>API: Batch Flushed
    Parser->>DB: Rebuild Secondary Indexes (Stage 2: INDEX)
    DB-->>API: Indexes Ready
    deactivate DB
    deactivate Parser

    API->>Graph: Build Directed Call Graph (Stage 3: GRAPH)
    API->>Graph: Precompute 2D/3D Matrices (Stage 4: LAYOUT)
    API->>Graph: Calculate Martin Instability (Stage 5: MODULES)
    API->>DB: Audit 14 Class Rules (Stage 6: INTEGRITY)
    
    API->>Story: Discover Transaction Storylines (Stage 7: CODESTORY)
    activate Story
    Story->>DB: Map Entry Points to Persistent Sinks
    Story-->>API: Storylines & 5-Layer Guided Tour Built
    deactivate Story

    API->>DB: Precompute 14 Intelligence Reports (Stage 8: REPORTS)
    API-->>UI: SSE Progress Complete (100% Ready)
    
    Dev->>UI: Click "Explore CodeStory"
    UI->>Story: GET /api/storylines
    Story-->>UI: Return Synthesized Narratives & Steps
    UI-->>Dev: Mount Interactive Storyline Player & Stepper Dock
```

---

## 🌊 Ingestion & CodeStory Data Flow Pipeline

Interactive Archify HTML: [`docs/diagrams/dataflow.html`](./docs/diagrams/dataflow.html)  
Specification: [`docs/diagrams/dataflow.json`](./docs/diagrams/dataflow.json)

```mermaid
flowchart LR
    subgraph Stage1["1. Source Code"]
        Java["📄 Java Sources\n(.java files)"]
    end
    subgraph Stage2["2. AST Parsing"]
        AST["🔍 JavaParser AST\n(types, methods, fields)"]
    end
    subgraph Stage3["3. Embedded Store"]
        H2DB[("🗄️ H2 MVStore\n(LZF compressed)")]
        LuceneDB[("🔎 Lucene Index\n(symbol tokens)")]
    end
    subgraph Stage4["4. Analytics Engine"]
        Topology["🕸️ Topology Core\n(JGraphT BFS)"]
        CodeStory["📖 CodeStory Engine\n(narratives & tour)"]
        Reports["📊 Reports Engine\n(14 audits)"]
    end
    subgraph Stage5["5. Workspaces"]
        Canvas["📊 Graphify 2D Canvas"]
        StoryPlayer["🎬 Storyline Player"]
        City3D["🏙️ 3D City & Reports"]
    end

    Java --> AST
    AST --> H2DB
    AST --> CodeStory
    AST --> LuceneDB
    H2DB --> Topology
    LuceneDB --> Reports
    Topology --> Canvas
    CodeStory --> StoryPlayer
    Reports --> City3D
```

---

## ⏱️ Server & Ingestion Lifecycle

Interactive Archify HTML: [`docs/diagrams/lifecycle.html`](./docs/diagrams/lifecycle.html)  
Specification: [`docs/diagrams/lifecycle.json`](./docs/diagrams/lifecycle.json)

```mermaid
stateDiagram-v2
    [*] --> JVM_Boot: Start JAR
    JVM_Boot --> Idle_Ready: Bind Port :7878 & Init H2
    Idle_Ready --> AST_Parse: Trigger Scan (Stage 1)
    AST_Parse --> Index_Rebuild: AST Parsed (Stage 2)
    Index_Rebuild --> Topology_Calc: Symbols Indexed (Stages 3-5)
    Topology_Calc --> Integrity_Audit: Matrices Cached (Stage 6)
    Integrity_Audit --> CodeStory_Synth: 14 Rules Verified (Stage 7)
    CodeStory_Synth --> Reports_Precompute: Storylines Mapped (Stage 8)
    Reports_Precompute --> Workspace_Live: All 8 Stages 100% Ready
    AST_Parse --> Scan_Interrupted: Fatal Abort / Error
    Scan_Interrupted --> [*]
    Workspace_Live --> Process_Hub: Process Telemetry & Sentinel
    Process_Hub --> AST_Parse: Incremental File Delta
    Workspace_Live --> Clean_Shutdown: SIGTERM / Exit
    Clean_Shutdown --> [*]
```

---

## 📦 Subsystem Architecture & Modules

| Module | Responsibility | Core Technologies |
|---|---|---|
| `codelens-core` | Domain entities, configuration models, progress tracking, and zero-dependency DTOs. | Pure Java 17 |
| `codelens-parser` | High-throughput parallel AST parsing, type hierarchy extraction, and field reference detection. | JavaParser 3.25.8 |
| `codelens-storage` | Embedded transactional persistence, bulk chunk insertion, MVStore compression, and full-text code search. | H2 Database 2.2.224, Apache Lucene 9.10.0, HikariCP |
| `codelens-analysis` | Directed call graphs, field mutation propagation, DSM permutation, Martin instability metrics, structural integrity audit, and 14 intelligence reports. | JGraphT 1.5.2, Custom Graph Algorithms |
| `codelens-git` | Git history analysis, author blame attribution, commit frequency scoring, churn heatmap calculation, and Git PR change storylines. | Eclipse JGit 6.9.0 |
| `codelens-api` | Lightweight REST routing, Server-Sent Events (SSE) streaming, WebSocket channels, CodeStory narrative synthesis, and JVM diagnostic controls. | Javalin 6.1.3, Jetty 11 |
| `codelens-web` | Client-side application featuring 2D Graphify Canvas, 3D Software City/Galaxy, Hierarchical Treemap/Sunburst, Storyline Player, and Monaco Editor. | Native ES6, Three.js, Monaco Editor, Lucide Icons |
| `codelens-app` | Application entry point, CLI subcommand runner (`scan`, `story`, `trace`, `what-if`, `ask`, `explain`, `teach-me`, `pr-story`), and shaded fat-JAR packager. | Maven Shade Plugin |

---

## 🚀 Key Architectural Guarantees

1. **100% Offline & Air-Gapped**: Zero external CDN calls, zero cloud telemetry, and zero mandatory external database services.
2. **Deterministic & Sequential Readiness**: The scan engine enforces all 8 stages (`PARSE`, `INDEX`, `GRAPH`, `LAYOUT`, `MODULES`, `INTEGRITY`, `CODESTORY`, `REPORTS`) sequentially, guaranteeing that every downstream view and report is fully populated before completion is declared.
3. **High-Scale Performance**: Handles 125,000+ classes via LZF page compression, Level-of-Detail (LOD) viewport culling, and sparse matrix representations.
4. **Self-Healing JVM Operations**: Integrated Process Hub monitors 11 background worker engines, featuring automated heap pressure detection, adaptive cache shedding, and an emergency memory circuit breaker.
