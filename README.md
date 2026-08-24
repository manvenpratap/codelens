# CodeLens — Java Codebase Intelligence Platform

A high-performance, **offline**, self-contained Java codebase intelligence and architectural exploration tool. Scan any Java source repository to extract Abstract Syntax Trees (AST), map dependency and call hierarchies, investigate repository-wide field mutation impacts, identify structural drift, audit Git author churn, and inspect source files with syntax highlighting — all served locally through an interactive cyber-dark web interface.

---

## Table of Contents

- [Table of Contents](#table-of-contents)
- [Overview & Capabilities](#overview--capabilities)
- [Architecture & Tech Stack](#architecture--tech-stack)
- [System Requirements & Installation](#system-requirements--installation)
- [Build & Run Guide](#build--run-guide)
- [Shipping & Deployment Guide (New Computer)](#shipping--deployment-guide-new-computer)
  - [Method 1: Standalone Single-File Fat JAR (Fastest)](#method-1-standalone-single-file-fat-jar-fastest)
  - [Method 2: Building from Source on the New Machine](#method-2-building-from-source-on-the-new-machine)
  - [Method 3: Docker Container Deployment](#method-3-docker-container-deployment)
  - [Method 4: Production Linux Systemd Service](#method-4-production-linux-systemd-service)
  - [Method 5: macOS LaunchAgent Daemon](#method-5-macos-launchagent-daemon)
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
| 7 | **Export Reports Hub** | Export comprehensive Architecture, Security/Quality Audit, and Inventory/Metrics reports in Markdown, standalone HTML, structured JSON, and tabular CSV with print-to-PDF support. | ReportService + REST export endpoints + Standalone HTML templates |
| 8 | **125k Classes Scalability Engine** | Multi-tier quotient graph rollups, Level-of-Detail (LOD) sub-pixel culling in Treemap/Sunburst/Chord/Graphify, and Sparse DSM matrix grids maintaining sub-100ms API response and 60 FPS UI rendering. | SQL-level aggregation queries + Viewport culling + Sparse DSM payload |

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

## System Requirements & Non-Admin Execution

- **Target Machine OS**: macOS (Intel / Apple Silicon), Linux (Ubuntu, Debian, RHEL, Arch), or Windows 10/11
- **Java Runtime Environment**: **JRE / JDK 17+** (`java -version`)
  - *macOS*: `brew install openjdk@17`
  - *Ubuntu / Debian*: `sudo apt install openjdk-17-jre-headless`
  - *RHEL / Fedora*: `sudo dnf install java-17-openjdk`
  - *Windows*: Download from [Eclipse Temurin](https://adoptium.net/)

> [!NOTE]
> **Zero Admin Rights Required (100% User-Space Execution)**
> - CodeLens **never** requires Administrator / `sudo` / `root` permissions.
> - **Unprivileged Port**: Runs by default on port `7878` (any port $>1024$ can be bound without admin privileges).
> - **Local Data Directory**: Writes its embedded H2 database and Lucene index strictly to the local directory (`./codelens-data` or `~/.codelens-data`) without touching system directories (`C:\Program Files`, `/var`, `/etc`).
> - **Portable JRE (No installer needed)**: If you cannot install Java on a locked-down corporate Windows machine, simply extract a `.zip` build of [Eclipse Temurin 17 JRE](https://adoptium.net/temurin/releases/?version=17) into any user folder (e.g. `C:\Users\You\jre17`) and run `C:\Users\You\jre17\bin\java.exe -jar codelens.jar`.

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

## Shipping & Deployment Guide (New Computer)

CodeLens is designed as a **zero-dependency, single-binary distribution**. The fat JAR packages the Javalin/Jetty web server, Lucene search engine, H2 database, static UI assets, and AST parser into a single self-contained executable.

### Method 1: Standalone Single-File Fat JAR (Fastest)

You only need to transfer **one file** to the target machine.

1. **Build the Fat JAR on your development machine**:
   ```bash
   ./mvnw clean package -DskipTests
   ```
2. **Copy the JAR to the new computer**:
   Transfer `codelens-app/target/codelens-app-1.0.0.jar` via `scp`, USB drive, or internal artifact registry:
   ```bash
   scp codelens-app/target/codelens-app-1.0.0.jar user@remote-machine:/opt/codelens/codelens.jar
   ```
3. **Run on the new machine** (requires only Java 17+):
   ```bash
   java -jar codelens.jar
   ```
4. Access `http://localhost:7878` in any browser.

---

### Method 2: Building from Source on the New Machine

If shipping the source repository (or cloning via Git):

1. **Clone repository**:
   ```bash
   git clone <repo-url> codelens
   cd codelens
   ```
2. **Build using the embedded Maven Wrapper** (no Maven installation required):
   ```bash
   # On macOS / Linux
   ./mvnw clean package -DskipTests

   # On Windows PowerShell
   .\mvnw.cmd clean package -DskipTests
   ```
3. **Run**:
   ```bash
   java -jar codelens-app/target/codelens-app-1.0.0.jar
   ```

---

### Method 3: Docker Container Deployment

To run CodeLens inside an isolated container:

1. **Create a `Dockerfile`** in the project root:
   ```dockerfile
   # Stage 1: Build fat JAR
   FROM eclipse-temurin:17-jdk-jammy AS builder
   WORKDIR /build
   COPY . .
   RUN chmod +x ./mvnw && ./mvnw clean package -DskipTests

   # Stage 2: Minimal runtime image
   FROM eclipse-temurin:17-jre-jammy
   WORKDIR /app
   COPY --from=builder /build/codelens-app/target/codelens-app-1.0.0.jar app.jar

   VOLUME /app/data
   VOLUME /sources

   EXPOSE 7878
   ENTRYPOINT ["java", "-Dcodelens.data=/app/data", "-jar", "app.jar"]
   ```

2. **Build and Run Docker Image**:
   ```bash
   # Build the container
   docker build -t codelens:1.0.0 .

   # Run container mounting your local source folder and persistent data volume
   docker run -d \
     --name codelens \
     -p 7878:7878 \
     -v codelens_data:/app/data \
     -v /path/to/your/java/code:/sources:ro \
     codelens:1.0.0
   ```

3. Scan the mounted code in the web UI by entering `/sources` as the source path.

---

### Method 4: Production Linux Systemd Service

To run CodeLens as a background system daemon on Linux servers:

1. Copy `codelens-app-1.0.0.jar` to `/opt/codelens/codelens.jar`.
2. Create `/etc/systemd/system/codelens.service`:
   ```ini
   [Unit]
   Description=CodeLens Java Intelligence Platform
   After=network.target

   [Service]
   Type=simple
   User=codelens
   WorkingDirectory=/opt/codelens
   ExecStart=/usr/bin/java -Xms512m -Xmx2g -Dcodelens.port=7878 -Dcodelens.data=/var/lib/codelens -jar /opt/codelens/codelens.jar
   Restart=always
   RestartSec=5

   [Install]
   WantedBy=multi-user.target
   ```
3. Enable and start the service:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now codelens
   sudo systemctl status codelens
   ```

---

### Method 5: macOS LaunchAgent Daemon

To run CodeLens automatically at login in the background on macOS:

1. Place the JAR at `~/Applications/CodeLens/codelens.jar`.
2. Create `~/Library/LaunchAgents/com.codelens.server.plist`:
   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
   <plist version="1.0">
   <dict>
       <key>Label</key>
       <string>com.codelens.server</string>
       <key>ProgramArguments</key>
       <array>
           <string>/usr/bin/java</string>
           <string>-jar</string>
           <string>/Users/yourusername/Applications/CodeLens/codelens.jar</string>
       </array>
       <key>RunAtLoad</key>
       <true/>
       <key>KeepAlive</key>
       <true/>
       <key>StandardOutPath</key>
       <string>/tmp/codelens.log</string>
       <key>StandardErrorPath</key>
       <string>/tmp/codelens-err.log</string>
   </dict>
   </plist>
   ```
3. Load the daemon:
   ```bash
   launchctl load ~/Library/LaunchAgents/com.codelens.server.plist
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
│             [ 8 types ] [ 72 methods ] [ 31 fields ] [ 230 rels ]                     │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Source Path Input**: Accepts absolute filesystem paths to Java source trees.
- **Unified Master Browse… Button**: Opens the native folder picker to select any Java repository. Automatically syncs Git repository discovery and source roots in one click.
- **Scan Button**: Runs the JavaParser AST visitor, H2 batch storage, Lucene search index rebuild, in-memory call graph compilation, and Git blame analysis.
- **❓ Guide Button**: Opens the in-app feature walkthrough, standard IDE symbol legend, and keyboard shortcuts modal.
- **Live Metric Counters**: Displays real-time counts for parsed Types, Methods, Fields, and Relationships.
- **Animated Scan Progress Bar**: Sits flush under the header, displaying scan phases (AST Parsing, Call Graph, Field Impact, Git Blame, Lucene Indexing).

---

### 2. Left Panel: Explorer & Lucene Search

```
┌─────────────────────────────────────────────────┐
│ ⌕ Search classes, methods, fields… (⌘K)         │
│ [ All ] [ Class ] [ Iface ] [ Enum ] [ Record ] │
│ Explorer                                        │
│ ▾ com.example.trading                           │
│   🔷 Portfolio                                  │
│   🔷 TradeProcessor                             │
│   🔷 RiskEngine                                 │
└─────────────────────────────────────────────────┘
```

- **Lucene Full-Text Search (`⌘K` / `Ctrl+K`)**:
  - Sub-millisecond indexed search across all classes, interfaces, enums, records, methods, fields, and signatures.
  - Pressing <kbd>Esc</kbd> clears search results and restores the package hierarchy.
- **Stereotype Filter Chips (`All`, `Class`, `Iface`, `Enum`, `Record`)**:
  - Instantly filters the explorer tree to display only matching entity kinds.
- **Package Hierarchy Tree**:
  - Collapsible tree view of packages and types.
  - Clicking any type displays its full detail in the Inspector panel and switches to the Knowledge Base.
  - Toggle panel visibility with the collapse button or <kbd>[</kbd>.

---

### 3. Centre Workspace Tabs

#### A. Graph Canvas (Graphify Knowledge Graph & Force Physics)
The interactive Canvas 2D engine visualises architectural hierarchies, call paths, and data mutation chains with standard IDE notation.

```
┌────────────────────────────────────────────────────────────────────────────────┐
│ [⌕ Find in graph…] [Depth: 1 2 3 5 Max] [◈ Clusters] [⏻ Physics] [♨ Heat] [⊞]   │
│                                                                                │
│   ╭────────────── com.example.trading ───────────────╮                         │
│   │                                                  │  [Floating Inspector]   │
│   │     (m: recordTrade) ──CALLS──> (m: updateCash)  │  ┌────────────────────┐ │
│   │            │                                     │  │ m recordTrade      │ │
│   │       WRITES_FIELD                               │  │ Type: METHOD       │ │
│   │            ▼                                     │  │ Degree: 4 (In:1,2) │ │
│   │     (f: cashBalance)                             │  │ Neighbors: [links] │ │
│   ╰──────────────────────────────────────────────────╯  └────────────────────┘ │
│                                                                 [Minimap Radar]│
└────────────────────────────────────────────────────────────────────────────────┘
```

- **Standard IDE Entity Symbols**:
  - **`m`** → Method (Light Blue / Cobalt)
  - **`f`** → Field / Property (Amber)
  - **`C`** → Class (Royal Blue)
  - **`I`** → Interface (Emerald Green)
  - **`E`** → Enum (Rose Coral)
  - **`R`** → Record (Teal)
- **Multi-Community Convex Hulls (`◈ Clusters`)**:
  - Packages and architectural domains are automatically rendered as shaded convex hulls with dynamic community colors.
- **Live Force Physics Engine (`⏻ Physics` / <kbd>Space</kbd>)**:
  - **Physics ON**: Dynamic force equilibrium calculating Coulomb repulsion, Hooke spring tension, package clustering, and multi-pass anti-collision separation. Connected nodes flex and react organically when dragged.
  - **Physics OFF (Frozen)**: Freezes node positions instantly for custom manual diagramming and lower CPU usage.
- **Git Commit Churn Heatmap (`♨ Heat` / <kbd>H</kbd>)**:
  - Shaders highlight frequently modified code entities with golden/red thermal glows based on Git commit frequency.
- **Quick Node Search (`⌕ Find in graph…`)**:
  - Dropdown search box directly inside the graph HUD to quickly locate and zoom to any node.
- **Configurable BFS Depth (1, 2, 3, 5, Max)**:
  - Dynamically traverses upstream callers and downstream dependencies up to 15 hops.
- **Floating Quick Node Inspector**:
  - Draggable, viewport-clamped card showing entity metadata, in/out degrees, and direct neighbor navigation links.
- **Minimap Radar**:
  - Bottom-right live radar overview for spatial awareness in large graphs.

#### B. Knowledge Base View
- Structured catalog of all classes and members within scanned packages.
- Lists field types, modifiers, and source line numbers.
- Computes **Cyclomatic Complexity (CC)** scores for every method with colored health indicators (`Green: 1-4`, `Amber: 5-10`, `Red: 11+`).

#### C. On-Demand Code Review & Logic Auditor
- 32 deep AST-based static checks across 6 quality categories:
  1. **Correctness**: Null pointer risks, unclosed streams, switch fallthroughs.
  2. **Concurrency**: Non-thread-safe mutation, unsynchronized state access.
  3. **Exception Safety**: Swallowed exceptions, raw `Throwable` catches.
  4. **Code Smells**: Long parameter lists, excessive cyclomatic complexity.
  5. **API Contracts**: Missing documentation on public APIs, broken equals/hashCode contracts.
  6. **Blast Radius Impact**: High-centrality method mutation risks.

#### D. Git Analytics & Churn Heatmap
- **Top Authors Leaderboard**: Ranked table of code contributors, total entities touched, and latest commit dates.
- **♨ Hottest Entities**: Real-time churn bar chart highlighting the most frequently modified classes, methods, and fields.

#### E. Integrated Monaco Source Code Editor
- Embedded Microsoft Monaco editor (the engine powering VS Code).
- Click any **Source line link** (e.g. `Portfolio.java:42`) in the inspector or search results to open the file directly in Monaco, jumping automatically to that line with syntax highlighting.
- Modify source code and click **Save** to persist changes directly to disk.

---

### 4. Right Inspector Panel & Analyst Notes

- **Entity Header**: Displays standard IDE entity badge, simple name, and Fully Qualified Name (FQN).
- **Metadata Grid**: Lists declaring class, return type, field type, modifiers, parameter signatures, line ranges, and clickable source links.
- **Git Telemetry Box**: Displays total commit count, primary author, churn risk level, and last modified timestamp.
- **Cyclomatic Complexity Meter**: Visual risk bar reflecting method complexity.
- **Action Triggers**:
  - `⬆ Callers`: Visualizes upstream callers in the Graph view.
  - `⬇ Callees`: Visualizes downstream dependencies in the Graph view.
  - `⚡ Impact (Direct)`: Shows immediate readers, writers, and propagators for a field.
  - `🔗 Propagation Chain`: Traces repository-wide upstream triggers of field mutations.
- **Analyst Notes Section**: Free-text markdown notes persisted to the embedded database per entity. Add observations, refactoring reminders, or security notes.

---

### 5. Interactive Footer & Realtime Status

- **Status Indicator**: Live pulsating indicator showing analyzer status (`Analyzer Idle` or `Scanning…`).
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
| `Esc` | Clear search query / Close active modal dialog / Deselect node | Global |
| `1` | Switch to **Graph** workspace | Global |
| `2` | Switch to **Knowledge Base** workspace | Global |
| `3` | Switch to **Code Review & Logic Auditor** workspace | Global |
| `4` | Switch to **Git Analytics** workspace | Global |
| `5` | Switch to **Source Code Editor** workspace | Global |
| `[` | Toggle **Left Explorer Panel** | Global |
| `]` | Toggle **Right Inspector Panel** | Global |
| `?` | Open / Close **Feature Guide Modal** | Global |
| `Space` | Toggle / Freeze **Live Force Physics Simulation** | Graph view |
| `H` | Toggle **Git Commit Churn Heatmap** on Graph nodes | Graph view |
| `F` | **Fit Entire Graph** to viewport | Graph view |

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

### Methods, Call Graphs & Architectural Views
- `GET /api/methods/{fqn}` — Method details, parameters, lines, and cyclomatic complexity
- `GET /api/methods/{fqn}/graph?depth=3` — Call hierarchy graph view (`nodes` + `edges`) up to `depth` hops
- `GET /api/methods/{fqn}/callers?depth=4` — Upstream caller hierarchy
- `GET /api/methods/{fqn}/callees?depth=4` — Downstream callee hierarchy
- `GET /api/graph/architecture?scope=module|package|class` — Quotient architecture graph view aggregated at module, package, or class level
- `GET /api/graph/dsm?scope=module|package|class` — Sparse Dependency Structure Matrix payload with fast cell lookup array
- `GET /api/graph/treemap?scope=module|package|class` — Hierarchical treemap payload aggregated by lines of code and complexity

### Reports & Export Hub
- `GET /api/reports/architecture?format=markdown|html|json|csv` — Architecture & Coupling metrics summary report
- `GET /api/reports/review?format=markdown|html|json|csv` — Code Quality & Security Audit report
- `GET /api/reports/metrics?format=markdown|html|json|csv` — Codebase Inventory & Metrics report
- `GET /api/reports/download?type=architecture|review|metrics&format=markdown|html|json|csv` — Direct file download endpoint for offline reports and PDF printing

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
