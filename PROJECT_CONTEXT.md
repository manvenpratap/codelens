# CodeLens — Project Context & System Architecture

> **Java Codebase Intelligence & AST Architectural Exploration Platform**  
> 100% offline self-contained codebase intelligence tool scanning Java source code to extract AST hierarchies, JGraphT call graphs, field mutation propagation, and 3D visualizers.

---

## 🏛️ System Architecture

Interactive Archify HTML: [`docs/diagrams/architecture.html`](./docs/diagrams/architecture.html)  
Specification: [`docs/diagrams/architecture.json`](./docs/diagrams/architecture.json)

```mermaid
flowchart TD
    User["👤 Developer / Architect\n(Verlet Canvas / 3D Three.js)"]
    
    subgraph JVMBoundary["☕ JVM Fat-JAR Runtime (Zero Cloud Dependencies)"]
        API["⚡ Javalin Server\n(Embedded Jetty REST / WS :8080)"]
        
        Parser["🔍 AST Parser\n(JavaParser 3.25.8)"]
        Analyzer["🕸️ Analysis Core\n(JGraphT Call Graph & DSM)"]
        Git["📜 Git Churn Engine\n(JGit Blame & Heatmap)"]
        
        subgraph StorageLayer["💾 Storage Subsystem"]
            H2[("🗄️ H2 Database\n(LZF MVStore Compressed)")]
            Lucene[("🔎 Lucene Search\n(Full-Text Code Index)")]
        end
    end

    User -->|"REST / WS"| API
    API -->|"trigger scan"| Parser
    API -->|"query graph"| Analyzer
    API -->|"git blame"| Git
    Parser -->|"batch insert"| H2
    Parser -->|"tokenize"| Lucene
    Analyzer -->|"read entities"| H2
```

---

## 🔄 Ingestion & Analysis Workflow

Interactive Archify HTML: [`docs/diagrams/workflow.html`](./docs/diagrams/workflow.html)  
Specification: [`docs/diagrams/workflow.json`](./docs/diagrams/workflow.json)

```mermaid
flowchart LR
    A["1. Scan Request\n(CLI / Web UI trigger)"] --> B["2. JavaParser Walk\n(Extract AST records)"]
    B --> C["3. Bulk H2 Commit\n(LZF chunk write)"]
    C --> D["4. Build JGraphT\n(Resolve call edges)"]
    D --> E["5. Compute Churn\n(JGit + CC metrics)"]
    E --> F["6. Load Workspace\n(Canvas / 3D City / DSM)"]
```

---

## ⚡ Scan & Call Graph Execution Sequence

Interactive Archify HTML: [`docs/diagrams/sequence.html`](./docs/diagrams/sequence.html)  
Specification: [`docs/diagrams/sequence.json`](./docs/diagrams/sequence.json)

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Developer
    participant API as Javalin API
    participant Parser as JavaParser Worker
    participant DB as Storage Core (H2/Lucene)
    participant Graph as JGraphT Graph Engine
    participant UI as Workspace UI

    Dev->>API: POST /api/scan
    API->>Parser: Start Async AST Walk
    activate Parser
    Parser->>DB: Stream Entity Chunks
    activate DB
    DB-->>API: Batch Flushed
    deactivate DB
    Parser-->>API: Scan Complete
    deactivate Parser
    API-->>UI: WebSocket Progress 100%
    UI->>Graph: GET /api/graph/call-hierarchy
    Graph-->>UI: Return BFS Nodes & Edges
    UI-->>Dev: Render Verlet Force Graph
```

---

## 🌊 Ingestion Data Flow Pipeline

Interactive Archify HTML: [`docs/diagrams/dataflow.html`](./docs/diagrams/dataflow.html)  
Specification: [`docs/diagrams/dataflow.json`](./docs/diagrams/dataflow.json)

```mermaid
flowchart LR
    subgraph Stage1["1. Sources"]
        Java["📄 Java Sources\n(.java files)"]
    end
    subgraph Stage2["2. AST Parse"]
        AST["🔍 JavaParser AST\n(types, methods, fields)"]
    end
    subgraph Stage3["3. Embedded Store"]
        H2DB[("🗄️ H2 MVStore")]
        LuceneDB[("🔎 Lucene Index")]
    end
    subgraph Stage4["4. Graph Computation"]
        JGraphT["🕸️ JGraphT BFS & Cycles"]
    end
    subgraph Stage5["5. Visualization"]
        Canvas["📊 Verlet Canvas 2D"]
        City["🏙️ 3D City & DSM"]
    end

    Java --> AST
    AST --> H2DB
    AST --> LuceneDB
    H2DB --> JGraphT
    JGraphT --> Canvas
    JGraphT -.-> City
```

---

## ⏱️ Server & Ingestion Lifecycle

Interactive Archify HTML: [`docs/diagrams/lifecycle.html`](./docs/diagrams/lifecycle.html)  
Specification: [`docs/diagrams/lifecycle.json`](./docs/diagrams/lifecycle.json)

```mermaid
stateDiagram-v2
    [*] --> JVM_Boot: Start JAR
    JVM_Boot --> Idle_Ready: Bind Port :8080 & Init H2
    Idle_Ready --> AST_Walk: Trigger Scan
    AST_Walk --> Index_Cache: AST Parsed
    AST_Walk --> Scan_Interrupted: Fatal Abort / Error
    Scan_Interrupted --> [*]
    Index_Cache --> Workspace_Live: Indices Ready
    Workspace_Live --> Delta_Watcher: File Watcher Active
    Delta_Watcher --> AST_Walk: File Modified
    Workspace_Live --> Clean_Shutdown: SIGTERM / Exit
    Clean_Shutdown --> [*]
```
