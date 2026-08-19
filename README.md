# CodeLens — Java Codebase Intelligence Platform

A high-performance, **offline**, self-contained Java codebase intelligence and architectural exploration tool. Scan any Java source repository to extract Abstract Syntax Trees (AST), map dependency and call hierarchies, investigate repository-wide field mutation impacts, identify structural drift, audit Git author churn, and inspect source files with syntax highlighting — all served locally through an interactive cyber-dark web interface.

---

## Table of Contents

- [Overview & Capabilities](#overview--capabilities)
- [Architecture & Tech Stack](#architecture--tech-stack)
- [System Requirements & Installation](#system-requirements--installation)
- [Build & Run Guide](#build--run-guide)
- [Scanning a Codebase](#scanning-a-codebase)
- [Detailed Screen & Feature Helper](#detailed-screen--feature-helper)
  - [1. Header & Navigation Controls](#1-header--navigation-controls)
  - [2. Left Panel: Explorer & Lucene Search](#2-left-panel-explorer--lucene-search)
  - [3. Centre Workspace Tabs](#3-centre-workspace-tabs)
    - [Graph Canvas (Dynamic BFS Depth & Physics)](#a-graph-canvas-dynamic-bfs-depth--physics)
    - [Knowledge Base View](#b-knowledge-base-view)
    - [Inconsistencies & Structural Drift Detector](#c-inconsistencies--structural-drift-detector)
    - [Git Analytics & Churn Heatmap](#d-git-analytics--churn-heatmap)
    - [Integrated Monaco Source Code Editor](#e-integrated-monaco-source-code-editor)
  - [4. Right Inspector Panel & Analyst Notes](#4-right-inspector-panel--analyst-notes)
  - [5. Interactive Footer & Realtime Status](#5-interactive-footer--realtime-status)
- [Impact Investigation Workflows](#impact-investigation-workflows)
  - [Method Blast Radius Analysis](#method-blast-radius-analysis)
  - [Field-to-Method Propagation Chains](#field-to-method-propagation-chains)
- [Keyboard Shortcuts](#keyboard-shortcuts)
- [REST API Reference](#rest-api-reference)
- [Data Storage & Persistence](#data-storage--persistence)
- [Troubleshooting & FAQ](#troubleshooting--faq)

---

## Overview & Capabilities

| # | Capability | Description | Technical Engine |
|---|---|---|---|
| 1 | **Source AST Indexing** | Scans 100% of `.java` source files, extracting packages, types, methods, fields, modifiers, and line numbers into an embedded database. | JavaParser 3.25.8 + Embedded H2 Database |
| 2 | **Dynamic Call Hierarchies** | Computes upstream callers and downstream callees across methods with user-selectable BFS traversal depths (1 to 15 hops / Max). | In-memory JGraphT directed graph + reversed BFS iterator |
| 3 | **Field Impact & Propagation Chains** | Maps every method that reads or writes a field, and traces multi-hop upstream triggers (`Field` $\leftarrow$ `Writers` $\leftarrow$ `Callers`) across the entire repository. | Custom relationship visitor + caller propagation engine |
| 4 | **Structural Inconsistency Detection** | 3-pass heuristic engine identifying signature divergences, naming drift, and duplicate AST body hashes across classes. | Levenshtein distance + AST Normalizer + SHA-256 body hashing |
| 5 | **Git Blame & Churn Heatmap** | Computes commit counts, top contributing authors, and churn frequency per entity, rendering commit heat directly on graph nodes. | JGit 6.9 engine + Dynamic Canvas Color Shaders |
| 6 | **Embedded Monaco Code Editor** | Jump directly from graph nodes, member lists, or relationship links to precise source code lines with full Java syntax highlighting. | Monaco Editor 0.45 + REST file reader/writer |

---

## Architecture & Tech Stack

```
codelens/
├── codelens-core/        Domain entities (CodeType, CodeMethod, CodeField, CodeRelationship, GitMeta, etc.)
├── codelens-parser/      JavaParser AST visitor, directory scanner, and relationship extractor
├── codelens-analysis/    In-memory call graph BFS, multi-hop field propagation, and inconsistency detector
├── codelens-storage/     H2 database lifecycle, connection pooling (HikariCP), and Apache Lucene indexer
├── codelens-git/         JGit repository locator, blame annotator, commit history extractor
├── codelens-api/         Javalin REST controller, file APIs, and scan orchestration
├── codelens-web/         Zero-build SPA (HTML5, Vanilla CSS design tokens, Canvas 2D Verlet physics, Monaco)
├── codelens-app/         Fat-JAR bootstrap and entry point (Application.java)
└── sample-project/       Realistic 5-class algorithmic trading system for demonstration and tests
```

### Architectural Decisions (ADRs)

- **AST Parsing (JavaParser 3.25.8)**: Full Java 17 record, sealed class, and pattern-matching support without needing heavy bytecode compilation or classpath dependencies.
- **Data Layer (H2 2.2.224 + Lucene 9.10.0)**: Zero-setup file-based persistence for SQL relations combined with Apache Lucene for sub-millisecond full-text entity search.
- **Graph Visualisation (Custom HTML5 Canvas 2D)**: Fully offline force-directed physics engine using Verlet integration. Zero D3 or external JS bundle overhead.
- **HTTP Layer (Javalin 6.1.3 + Jetty 11)**: Lightweight microframework delivering ultra-fast REST endpoints and static asset serving with embedded WebSockets.

---

## System Requirements & Installation

- **Operating System**: macOS, Linux, or Windows (x86_64 / Apple Silicon ARM64)
- **Java Runtime**: JDK 17 or higher (`java -version`)
- **Maven**: Maven 3.8+ or the included Maven Wrapper (`./mvnw`)

---

## Build & Run Guide

### 1. Build the Fat JAR
From the project root:
```bash
./mvnw clean package -DskipTests
```
The standalone executable JAR will be generated at:
```
codelens-app/target/codelens-app-1.0.0.jar
```

### 2. Launch the Application
```bash
java -jar codelens-app/target/codelens-app-1.0.0.jar
```
Once launched, open your web browser at: **`http://localhost:7878`**

### Optional JVM Flags
```bash
# Custom HTTP Port
java -Dcodelens.port=9090 -jar codelens-app/target/codelens-app-1.0.0.jar

# Custom Data & Index Directory
java -Dcodelens.data=/custom/path/codelens-data -jar codelens-app/target/codelens-app-1.0.0.jar
```

---

## Scanning a Codebase

### Method 1: Web UI
1. Open `http://localhost:7878`.
2. Paste the **absolute path** to your Java source directory (e.g. `/Users/you/project/src/main/java`) or click **Browse…**.
3. Click **Scan**.
4. The top progress bar and status footer will display parsing phases in realtime. The package tree, stats, and search index will automatically reload upon completion.

### Method 2: REST API
```bash
curl -X POST http://localhost:7878/api/scan \
     -H 'Content-Type: application/json' \
     -d '{"sourcePath":"/absolute/path/to/project/src/main/java"}'
```

### Using the Built-In Sample Project
```bash
# On macOS / Linux:
realpath sample-project/src/main/java
# Paste the resulting path into the scan bar in the UI.
```

---

## Detailed Screen & Feature Helper

### 1. Header & Navigation Controls

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ ⬡ CodeLens  [ /path/to/source/dir                    ] [ Browse… ] [ Scan ] [ ❓ Guide ] │
│             [ 8 types ] [ 72 methods ] [ 31 fields ]                                  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Source Path Input**: Accepts absolute filesystem paths to Java source trees.
- **Browse… Button**: Opens the native folder picker dialog to select project folders easily.
- **Scan Button**: Triggers the AST scanner, H2 batch inserts, Lucene index rebuild, call graph compilation, and Git blame analysis.
- **❓ Guide Button**: Opens the full interactive in-app documentation and feature guide modal.
- **Live Stats Pills**: Displays indexed entity counts (Types, Methods, Fields) with animated counters.
- **Scan Progress Bar**: Sits flush under the header, animating from 0% to 100% across scan phases.

---

### 2. Left Panel: Explorer & Lucene Search

```
┌───────────────────────────────┐
│ ⌕ Search… (⌘K)                │
│ [ All ] [ Class ] [ Iface ] [ Enum ] │
│ Explorer                      │
│ ▾ com.example.trading         │
│   🔷 Portfolio                │
│   🔷 TradeProcessor           │
│   🔷 RiskEngine               │
└───────────────────────────────┘
```

- **Lucene Full-Text Search (`⌘K` / `Ctrl+K`)**:
  - Sub-millisecond indexed search across all classes, interfaces, enums, methods, fields, and signatures.
  - Pressing `Esc` clears the search results and restores the package explorer.
- **Entity Filter Chips (`All`, `Class`, `Iface`, `Enum`)**:
  - Instantly toggles the explorer tree to display only specific entity stereotypes.
- **Package Hierarchy Tree**:
  - Collapsible tree view of packages and types.
  - Clicking any type displays its full detail in the right panel and switches to the Knowledge Base.

---

### 3. Centre Workspace Tabs

#### A. Graph Canvas (Dynamic BFS Depth & Physics)
The interactive force-directed graph canvas visualises architectural call paths and data mutations.

```
┌──────────────────────────────────────────────────────────────┐
│ [Legend]                              [Scan Depth: 3 hops]   │
│ 🟣 Selected Method                    [---●------] [1 2 3 5 8 Max] │
│ 🔵 Caller (Calls this)                                       │
│ 🟢 Callee (Called by)                                        │
│                                                              │
│                   (Node) ──CALLS──> (Node)                   │
│                                                              │
│                                           [🔥] [⊞] [⊠]       │
└──────────────────────────────────────────────────────────────┘
```

- **Force Physics & Manipulation**:
  - **Pan & Zoom**: Click and drag empty space to pan; scroll wheel to zoom in/out.
  - **Node Dragging**: Click and drag any node to anchor its position; double-click or fit to release.
  - **Node Inspection**: Clicking any node navigates directly to that method or field in the Inspector panel.
- **Dynamic Depth Slider & Quick Pills**:
  - Adjust the BFS traversal depth from **1** to **10** (or click **Max / 15 hops**).
  - The graph dynamically re-evaluates and expands without deselecting your current entity.
- **Toolbar Overlay (Bottom-Right)**:
  - **🔥 Heat Overlay (`H`)**: Toggles Git churn heat visualization. Hotly modified methods pulse with red/orange outer radial glows.
  - **⊞ Fit View (`F`)**: Automatically recalibrates camera zoom and centers the graph viewport around all active nodes.
  - **⊠ Clear Canvas**: Clears the current visual graph.

#### B. Knowledge Base View
- Structured breakdown of all types within selected packages.
- Lists each field's type, modifiers, and source line.
- Lists each method's parameter list, return type, modifiers, and computed **Cyclomatic Complexity (CC)** metric with color badges (`Green: 1-4`, `Amber: 5-10`, `Red: 11+`).

#### C. Inconsistencies & Structural Drift Detector
- Automatically flags code smells and maintenance hazards across three detection passes:
  1. **Signature Divergence**: Same-named methods across different classes with conflicting parameters or return types.
  2. **Naming Drift**: Classes or methods with >80% string similarity but diverging internal structures.
  3. **Duplicate AST Blocks**: Identical logic and method bodies detected via SHA-256 normalization.
- Displays a similarity percentage badge, entity pairing, and natural-language explanation for each flagged item.

#### D. Git Analytics & Churn Heatmap
- **Top Authors Leaderboard**: Ranked table of code authors, total entities touched, and latest commit dates.
- **🔥 Hottest Entities**: Real-time churn bar chart highlighting the most frequently modified classes, methods, and fields.

#### E. Integrated Monaco Source Code Editor
- Embedded Microsoft Monaco editor (the engine powering VS Code).
- Click any **Source line link** (e.g. `Portfolio.java:42`) in the inspector to open the file directly in Monaco, jumping automatically to that line with neon highlighting.
- Modify source code and click **Save** to persist changes directly to disk.

---

### 4. Right Inspector Panel & Analyst Notes

- **Entity Header**: Displays entity kind badge (`CLASS`, `INTERFACE`, `METHOD`, `FIELD`), simple name, and Fully Qualified Name (FQN).
- **Metadata Grid**: Lists declaring class, return type, field type, modifiers, parameter signatures, line ranges, and clickable source links.
- **Git Statistics Box**: Shows total commit count, primary author, churn risk level, and last modified date.
- **Cyclomatic Complexity Bar**: Visual meter reflecting method complexity risk.
- **Action Buttons**:
  - `⬆ Callers`: Visualizes upstream callers in the Graph view.
  - `⬇ Callees`: Visualizes downstream dependencies in the Graph view.
  - `⚡ Impact (Direct)`: Shows immediate readers, writers, and propagators for a field.
  - `🔗 Propagation Chain`: Traces repository-wide upstream triggers of field mutations.
- **Analyst Notes Section**: Free-text markdown notes persisted to the embedded database per entity. Add observations, refactoring reminders, or security notes.

---

### 5. Interactive Footer & Realtime Status

- **Status Indicator**: Live pulsating dot indicating analyzer status (`Idle` or `Scanning`).
- **Database & Git Branch**: Displays active database engine and the current Git branch.
- **Quick Keyboard Shortcut Hints**: Persistent reminder of core navigation shortcuts.

---

## Impact Investigation Workflows

### Method Blast Radius Analysis
1. Search or click any method (e.g. `TradeProcessor.recordTrade`).
2. Click **`⬆ Callers`** in the Right Inspector.
3. Use the **Scan Depth Slider** in the top-right of the Graph view to expand depth from `1` (direct callers) to `3` or `5` (transitive system entrypoints).
4. Identify which public APIs or controllers will be impacted if the method signature or logic changes.

### Field-to-Method Propagation Chains
1. Select any field (e.g. `Portfolio.cashBalance`).
2. Click **`🔗 Propagation Chain`** in the Right Inspector.
3. CodeLens traverses:
   $$\text{Field} \longleftarrow \text{Direct Writer Methods} \longleftarrow \text{Upstream Calling Triggers}$$
4. The canvas renders the complete mutation lineage with color-coded node roles:
   - 🟡 **Field** (Target variable)
   - 🔴 **Direct Writer** (Methods modifying the field)
   - 🔵 **Upstream Caller / Trigger** (Entry points invoking writers)
   - 🟢 **Direct Reader** (Methods accessing the field)

---

## Keyboard Shortcuts

| Shortcut | Action | Scope |
|---|---|---|
| `⌘K` / `Ctrl+K` | Focus global Lucene entity search | Global |
| `Esc` | Clear search query / Close active modal dialog | Global |
| `1` | Switch to **Graph** workspace | Global |
| `2` | Switch to **Knowledge Base** workspace | Global |
| `3` | Switch to **Inconsistencies** workspace | Global |
| `4` | Switch to **Git Analytics** workspace | Global |
| `5` | Switch to **Source Code Editor** workspace | Global |
| `?` | Open / Close **Feature Guide Modal** | Global |
| `H` | Toggle Git commit heat overlay on Graph nodes | Graph view |
| `F` | Fit graph simulation to viewport | Graph view |

---

## REST API Reference

All endpoints return JSON and are accessible locally at `http://localhost:7878/api`.

### Scan & Index Controls
- `POST /api/scan` — Start a background scan (`{"sourcePath": "/path/to/src"}`)
- `GET /api/scan/status` — Poll current scan progress and status
- `GET /api/scan/browse` — Open native OS directory chooser dialog
- `GET /api/stats` — Summary entity counts (`types`, `methods`, `fields`, `relationships`, `inconsistencies`)

### Packages & Types
- `GET /api/packages` — Flat list of indexed packages
- `GET /api/packages/{fqn}/types` — Types declared in a specific package
- `GET /api/types` — List all types (supports `?q=` search)
- `GET /api/types/{fqn}` — Detail for a type including member fields and methods

### Methods & Call Graphs
- `GET /api/methods/{fqn}` — Method details, parameters, lines, and cyclomatic complexity
- `GET /api/methods/{fqn}/graph?depth=3` — Call hierarchy graph view (`nodes` + `edges`) up to `depth` hops
- `GET /api/methods/{fqn}/callers?depth=4` — Upstream caller hierarchy
- `GET /api/methods/{fqn}/callees?depth=4` — Downstream callee hierarchy

### Fields & Impact Analysis
- `GET /api/fields/{fqn}` — Field metadata and initializer expression
- `GET /api/fields/{fqn}/impact?depth=1` — Field impact and multi-hop propagation chain (`readers`, `writers`, `propagators`, `graph`)

### Analysis & Search
- `GET /api/inconsistencies` — List of all flagged structural inconsistencies and AST clones
- `GET /api/search?q={query}&limit=30` — Apache Lucene full-text entity search

### Source Files & Git Metadata
- `GET /api/files/read?path={filePath}` — Read source file contents from disk
- `POST /api/files/write` — Save updated file content (`{"path": "...", "content": "..."}`)
- `GET /api/git/summary` — Top authors leaderboard and hot churn entities
- `GET /api/git/meta/{fqn}` — Git commit history and blame stats for an entity

### Analyst Notes
- `GET /api/notes/{entityFqn}` — Retrieve analyst notes for an entity
- `POST /api/notes` — Create or update a note (`{"entityFqn": "...", "content": "..."}`)
- `DELETE /api/notes/{id}` — Delete an analyst note

---

## Data Storage & Persistence

All database files and search indices are stored locally in `./codelens-data/`:

```
codelens-data/
├── codelens_db.mv.db       # Embedded H2 Database (AST schema, relationships, notes, git metrics)
├── codelens_db.trace.db    # H2 transaction trace log
└── lucene-index/           # Apache Lucene index directory shards
```

> [!TIP]
> **Resetting the Database**: To completely reset your indexed data, simply delete the `./codelens-data/` folder and initiate a fresh scan from the web interface.

---

## Troubleshooting & FAQ

#### Q: How do I change the default port from 7878?
Pass the `-Dcodelens.port=PORT` JVM argument when running the JAR:
```bash
java -Dcodelens.port=8080 -jar codelens-app/target/codelens-app-1.0.0.jar
```

#### Q: Does CodeLens send any code or telemetry outside my machine?
**No.** CodeLens is completely offline. AST parsing, H2 SQL storage, Lucene indexing, Git blame extraction, and Canvas 2D rendering execute 100% locally on your machine.

#### Q: What versions of Java can CodeLens scan?
CodeLens supports parsing Java source files from **Java 8 through Java 21+** (including records, pattern matching, switch expressions, and text blocks).
