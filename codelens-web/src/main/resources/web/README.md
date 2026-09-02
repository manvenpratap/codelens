# CodeLens — Java Codebase Intelligence Platform

A high-performance, **100% offline**, self-contained Java codebase intelligence and architectural exploration tool. Scan any Java source repository to extract Abstract Syntax Trees (AST), map dependency and call hierarchies, investigate repository-wide field mutation impacts, identify structural drift, audit Git author churn, and inspect source files with syntax highlighting — all served locally through an interactive cyber-dark web interface.

---

## Table of Contents

- [Overview & Core Capabilities](#overview--core-capabilities)
- [Architecture & Tech Stack](#architecture--tech-stack)
  - [System Architecture](#system-architecture)
  - [Architectural Decisions (ADRs)](#architectural-decisions-adrs)
- [System Requirements & Zero-Admin Execution](#system-requirements--zero-admin-execution)
- [Build & Quick Start](#build--quick-start)
- [Shipping & Deployment Handbook](#shipping--deployment-handbook)
  - [Method 1: Standalone Single-File Fat JAR (Fastest)](#method-1-standalone-single-file-fat-jar-fastest)
  - [Method 2: Building from Source on the New Machine](#method-2-building-from-source-on-the-new-machine)
  - [Method 3: Docker Container Deployment](#method-3-docker-container-deployment)
  - [Method 4: Production Linux Systemd Service](#method-4-production-linux-systemd-service)
  - [Method 5: macOS LaunchAgent Daemon](#method-5-macos-launchagent-daemon)
  - [JVM Tuning for Ultra-Large Repositories (50k–125k+ Classes)](#jvm-tuning-for-ultra-large-repositories-50k125k-classes)
- [Scanning Engine & Operational Guide](#scanning-engine--operational-guide)
  - [1. Full Scans vs Incremental Delta Scans](#1-full-scans-vs-incremental-delta-scans)
  - [2. Live Scan Cancellation](#2-live-scan-cancellation)
  - [3. Folder & File Exclusions](#3-folder--file-exclusions)
  - [4. Graceful Server Shutdown & Interrupted Scan Recovery](#4-graceful-server-shutdown--interrupted-scan-recovery)
  - [5. Automatic Package Hierarchy Handling](#5-automatic-package-hierarchy-handling)
- [Workspace Views & Feature Guide](#workspace-views--feature-guide)
  - [1. Header Bar & Project Telemetry](#1-header-bar--project-telemetry)
  - [2. Left Explorer & Lucene Search Engine](#2-left-explorer--lucene-search-engine)
  - [3. Interactive Center Workspace Views](#3-interactive-center-workspace-views)
    - [A. Graph Canvas (Graphify Knowledge Graph & Verlet Physics)](#a-graph-canvas-graphify-knowledge-graph--verlet-physics)
    - [B. Knowledge Base Catalog & Complexity Analysis](#b-knowledge-base-catalog--complexity-analysis)
    - [C. Visualizations Suite (3D City, 3D Galaxy, Treemap, Sunburst, DSM, Chord)](#c-visualizations-suite-3d-city-3d-galaxy-treemap-sunburst-dsm-chord)
    - [D. On-Demand Code Review & 32-Rule Static Auditor](#d-on-demand-code-review--32-rule-static-auditor)
    - [E. Git Analytics & Churn Heatmap](#e-git-analytics--churn-heatmap)
    - [F. Integrated Monaco Source Code Editor](#f-integrated-monaco-source-code-editor)
  - [4. Right Inspector Panel & Multi-Hop Propagation](#4-right-inspector-panel--multi-hop-propagation)
  - [5. Analyst Notes Engine](#5-analyst-notes-engine)
  - [6. Export Reports Hub (Markdown, HTML, JSON, CSV, PDF)](#6-export-reports-hub-markdown-html-json-csv-pdf)
- [Impact Investigation & Deep Architectural Workflows](#impact-investigation--deep-architectural-workflows)
  - [Method Blast Radius Analysis](#method-blast-radius-analysis)
  - [Field-to-Method Mutation Propagation Chains](#field-to-method-mutation-propagation-chains)
- [Data Storage, H2 Compaction & High-Scale Persistence](#data-storage-h2-compaction--high-scale-persistence)
- [Comprehensive REST API Reference](#comprehensive-rest-api-reference)
- [Keyboard Shortcuts](#keyboard-shortcuts)
- [Troubleshooting & FAQ](#troubleshooting--faq)

---

## Overview & Core Capabilities

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
| 9 | **Storage Compression & Compaction** | LZF compressed H2 page storage with post-scan MVStore compaction reducing disk footprints by ~95% (1–2 GB for 30k–50k classes). | H2 MVStore Compression + `SHUTDOWN COMPACT` |
| 10 | **Cooperative Scan Cancellation & Graceful Shutdown** | Zero-leak cooperative scan cancellation and API-driven graceful server shutdown with clean buffer flushes. | Dedicated Worker Pools + HikariCP Auto-commit Control |

---

## Architecture & Tech Stack

### System Architecture

```
codelens/
├── codelens-core/        Domain entities (CodeType, CodeMethod, CodeField, CodeRelationship, GitMeta, etc.)
├── codelens-parser/      JavaParser AST visitor, directory scanner, incremental delta detector
├── codelens-analysis/    In-memory call graph BFS, multi-hop field propagation, and 32-rule code reviewer
├── codelens-storage/     H2 database lifecycle, connection pooling (HikariCP), LZF compaction, Lucene indexer
├── codelens-git/         JGit repository locator, blame annotator, commit history extractor
├── codelens-api/         Javalin REST controller, file APIs, scan orchestration, shutdown handler
├── codelens-web/         Zero-build SPA (HTML5, Vanilla CSS design tokens, Canvas 2D Verlet physics, Three.js, Monaco)
├── codelens-app/         Fat-JAR bootstrap and entry point (Application.java)
└── sample-project/       Realistic 5-class algorithmic trading system for demonstration and tests
```

### Architectural Decisions (ADRs)

- **AST Parsing (JavaParser 3.25.8)**: Full Java 17 record, sealed class, and pattern-matching support without needing heavy bytecode compilation, maven builds, or runtime classpath dependencies.
- **Data Layer (H2 2.2.224 + Lucene 9.10.0)**: Zero-setup file-based persistence for SQL relations combined with Apache Lucene for sub-millisecond full-text entity search.
- **High-Speed Bulk Ingestion**: Disables and drops secondary B-tree indexes during full scans to maintain $O(1)$ batch insertion speeds regardless of existing table size, rebuilding indexes and running `ANALYZE` upon scan completion.
- **Graph Visualisation (Custom HTML5 Canvas 2D)**: Fully offline force-directed physics engine using Verlet integration. Zero D3 or external JS bundle overhead.
- **3D Visualizations (Three.js r128)**: Self-contained WebGL shaders and post-processing bloom/vignette passes for 3D City and 3D Galaxy views.
- **HTTP Layer (Javalin 6.1.3 + Jetty 11)**: Lightweight microframework delivering ultra-fast REST endpoints and static asset serving with embedded WebSockets.

---

## System Requirements & Zero-Admin Execution

- **Target Machine OS**: macOS (Intel / Apple Silicon), Linux (Ubuntu, Debian, RHEL, CentOS, Arch), or Windows 10/11
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

## Build & Quick Start

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

### Optional JVM Flags & Parameters
```bash
# Custom HTTP Port
java -Dcodelens.port=9090 -jar codelens-app/target/codelens-app-1.0.0.jar

# Custom Data & Index Directory
java -Dcodelens.data=/custom/path/codelens-data -jar codelens-app/target/codelens-app-1.0.0.jar

# Production Memory Allocation (for 30k+ file projects)
java -Xms1g -Xmx4g -XX:+UseG1GC -jar codelens-app/target/codelens-app-1.0.0.jar
```

---

## Shipping & Deployment Handbook

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

1. **Build Docker Image**:
   ```bash
   docker build -t codelens:1.0.0 .
   ```
2. **Run container mounting your local source folder and persistent data volume**:
   ```bash
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
   ExecStart=/usr/bin/java -Xms1g -Xmx4g -XX:+UseG1GC -Dcodelens.port=7878 -Dcodelens.data=/var/lib/codelens -jar /opt/codelens/codelens.jar
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
           <string>-Xms512m</string>
           <string>-Xmx2g</string>
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

### JVM Tuning for Ultra-Large Repositories (50k–125k+ Classes)

For massive multi-module enterprise monorepos (e.g. 50,000 to 125,000 `.java` files):

```bash
java -Xms2g -Xmx6g \
     -XX:+UseG1GC \
     -XX:G1ReservePercent=15 \
     -XX:InitiatingHeapOccupancyPercent=45 \
     -Dcodelens.port=7878 \
     -jar codelens.jar
```

---

## Scanning Engine & Operational Guide

### 1. Full Scans vs Incremental Delta Scans

CodeLens supports two intelligent scanning modes tailored for initial onboarding vs active development:

#### A. Full Rescan (`POST /api/scan`)
- **Use Case**: First-time repository indexing, major branch switches, or clean rebuilds.
- **Pipeline Stages**:
  1. `TRUNCATE TABLE` across all entity and relationship tables.
  2. Disables and drops secondary B-tree indexes for fast bulk ingestion.
  3. Parses all `.java` AST trees and inserts records in 10,000-row chunks using plain `INSERT INTO`.
  4. Rebuilds all secondary indexes and executes database `ANALYZE`.
  5. Runs **H2 MVStore Compaction** (`SHUTDOWN COMPACT`) with LZF compression.
  6. Rebuilds Apache Lucene full-text indices and in-memory JGraphT call hierarchies.

#### B. Incremental Delta Scan (`POST /api/scan/incremental`)
- **Use Case**: Day-to-day development after editing, pulling Git changes, or creating new classes.
- **Pipeline Stages**:
  1. Compares disk file modification timestamps and sizes against indexed metadata via `GET /api/scan/changes`.
  2. Identifies exact sets of **New**, **Modified**, and **Deleted** files.
  3. Purges database records **only for changed/deleted files** via `deleteBySourceFiles(...)`.
  4. Parses only the delta files and merges them using idempotent `MERGE INTO ... KEY(id)`.
  5. Updates Lucene documents and refreshes the in-memory call graph in sub-seconds.

```bash
# Trigger an incremental delta rescan via cURL
curl -X POST http://localhost:7878/api/scan/incremental \
     -H 'Content-Type: application/json' \
     -d '{
       "sourcePath": "/path/to/project",
       "excludePatterns": ["target", "build", ".mvn", ".git"]
     }'
```

---

### 2. Live Scan Cancellation

If a scan was started with an incorrect path or exclude pattern, you can cancel it immediately without corrupting data or leaking database connections:
- Click the **Cancel** button on the bottom floating scan progress bar.
- Or issue `POST /api/scan/cancel`.
- The scanner thread safely halts at the current chunk boundary, cleans up active transactions, and restores the UI to idle state.

---

### 3. Folder & File Exclusions

Configure directory names or globs to ignore non-essential files. Set these via the UI **⚙ Settings** modal or API:

```
Default Exclusions:
target, build, .mvn, .git, .gradle, node_modules, bin, out, **/test/**, *Test.java, */generated-sources/*
```

---

### 4. Graceful Server Shutdown & Interrupted Scan Recovery

- **Graceful Shutdown**: Click **Shutdown Server** in the **⚙ Settings** modal or POST to `/api/shutdown`. CodeLens cleanly flushes Lucene index writers, commits database transactions, closes HikariCP pools, and exits the process.
- **Interrupted Scan Recovery**: If the server process was terminated abruptly (e.g. system reboot or power outage) while a scan was active, CodeLens automatically detects the interrupted state on restart, marks the scan status as `INTERRUPTED`, and provides a one-click **Resume Rescan** button on the landing page.

---

### 5. Automatic Package Hierarchy Handling

CodeLens automatically discovers common namespace roots (e.g. `com.company.project.`) across all scanned packages and cleans them into human-readable module clusters (e.g. `trading.risk` → `Trading › Risk`) without requiring manual prefix configuration. Choose your preferred label style anytime in **⚙ Settings**:
- **Auto-Detect & Clean (Recommended)**: Groups under smart module roots.
- **Abbreviated (`c.e.trading`)**: Compact IDE-style package notation.
- **Full Qualified Name (FQN)**: Complete raw Java package strings.

---

## Workspace Views & Feature Guide

### 1. Header Bar & Project Telemetry

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ ⬡ CodeLens  [ /path/to/source/dir                    ] [ Browse… ] [ Scan ] [ ⚙ ] [ ❓ ]│
│             [ 124 types ] [ 890 methods ] [ 430 fields ] [ 2,140 rels ]                │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Source Path Input**: Accepts absolute filesystem paths to Java source trees.
- **Browse… Button**: Native directory chooser with automatic Git repository discovery.
- **Scan / Rescan Dropdown**: One-click triggers for Full Scan and Incremental Delta Scan.
- **⚙ Settings & Themes Modal**: Manage Dark (Obsidian OLED) vs Light (Daylight) themes, exclude patterns, package display modes, physics sliders, archetype engine rules, and server shutdown.
- **Realtime Entity Counters**: Instant counts for Types, Methods, Fields, and Relationships.

---

### 2. Left Explorer & Lucene Search Engine

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

- **Lucene Search (`⌘K` / `Ctrl+K`)**: Sub-millisecond indexed search across all classes, interfaces, enums, records, methods, fields, and signatures.
- **Kind Filter Chips**: Filter the explorer tree to display only Classes, Interfaces, Enums, or Records.
- **Collapsible Package Tree**: Full package hierarchy with instant member navigation.

---

### 3. Interactive Center Workspace Views

#### A. Graph Canvas (Graphify Knowledge Graph & Verlet Physics)
- **Verlet Physics Engine**: Real-time multi-body force simulation calculating Coulomb repulsion, Hooke spring tension, package clustering, and anti-collision.
- **Convex Hull Clustering (`◈ Clusters`)**: Visualizes architectural modules as colored translucent hulls.
- **Git Churn Heatmap (`♨ Heat` / <kbd>H</kbd>)**: Thermal glows highlight actively modified hotspots.
- **BFS Depth Controls (1–15 hops / Max)**: Explore transitive callers and callee dependencies.

#### B. Knowledge Base Catalog & Complexity Analysis
- Complete tabular catalog of all scanned types, member variables, and methods.
- Computes **Cyclomatic Complexity (CC)** scores for every method with color health badges:
  - 🟢 **Low Complexity (1–4)**: Clean, straightforward execution path.
  - 🟡 **Moderate Complexity (5–10)**: Branching logic requiring thorough unit tests.
  - 🔴 **High Complexity (11+)**: Heavy nesting; prime candidate for refactoring.

#### C. Visualizations Suite (3D City, 3D Galaxy, Treemap, Sunburst, DSM, Chord)
- 🏙️ **3D Software City**: Interactive Three.js urban layout mapping packages to city blocks, classes to skyscrapers, LOC to height, and complexity/churn to roof colors. Includes exposure controls.
- 🌌 **3D Galaxy**: Orbital gravitational star system rendering classes as stars orbiting central module clusters.
- 🗺️ **Treemap & Sunburst**: Hierarchical area partitioners with sub-pixel LOD culling.
- 📊 **Dependency Structure Matrix (DSM)**: Sparse grid matrix displaying coupling strength and circular dependencies.
- ⭕ **Chord Diagram**: Radial flows visualising cross-package references.

#### D. On-Demand Code Review & 32-Rule Static Auditor
32 deep AST static analysis rules across 6 critical quality categories:
1. **Correctness**: Null pointer hazards, unclosed streams, switch fallthroughs, array reference leaks.
2. **Concurrency**: Non-atomic shared state mutation, unsynchronized collections in multithreaded classes.
3. **Exception Safety**: Swallowed exceptions, catching `Throwable`, throwing raw runtime exceptions.
4. **Code Smells**: God classes, long parameter lists ($>5$), high cyclomatic complexity ($>15$).
5. **API Contracts**: Missing interface contracts, broken `equals`/`hashCode` symmetry.
6. **Architectural Blast Radius**: Core utility methods with extreme upstream caller fan-in.

#### E. Git Analytics & Churn Heatmap
- **Top Authors Leaderboard**: Contribution metrics, entities touched, and recent commit dates.
- **Hot Churn Entities**: Ranked bar chart of highest-churn classes and methods.

#### F. Integrated Monaco Source Code Editor
- Embedded Microsoft Monaco editor (VS Code engine).
- Click any source code link (e.g. `OrderService.java:142`) to jump directly to the exact line number with full syntax highlighting.
- In-place editing and direct save back to disk.

---

### 4. Right Inspector Panel & Multi-Hop Propagation

- **Metadata Card**: Displays modifiers, inheritance, implemented interfaces, lines, and Git history.
- **Action Triggers**:
  - `⬆ Callers`: Visualizes upstream callers in the Graph view.
  - `⬇ Callees`: Visualizes downstream dependencies in the Graph view.
  - `⚡ Impact (Direct)`: Shows immediate readers, writers, and propagators for a field.
  - `🔗 Propagation Chain`: Traces repository-wide upstream triggers of field mutations.

---

### 5. Analyst Notes Engine

- Free-text markdown notes attached to any class, interface, method, or field.
- Persisted locally in the H2 database and included in exported reports.

---

### 6. Export Reports Hub (Markdown, HTML, JSON, CSV, PDF)

Generate and export executive and technical reports in multiple formats:
- **Architecture & Coupling Report**: Package quotient metrics, cyclic dependency audits, afferent/efferent coupling ($C_a$, $C_e$), and instability metrics ($I = C_e / (C_a + C_e)$).
- **Code Quality & Security Audit**: Complete list of all 32-rule violations, severities, and line references.
- **Inventory & Metrics Report**: Detailed breakdown of LOC, cyclomatic complexity, and member counts.
- **Export Formats**: Markdown (`.md`), Standalone HTML (`.html`), Structured JSON (`.json`), Tabular CSV (`.csv`), and browser-native Print to PDF.

---

## Impact Investigation & Deep Architectural Workflows

### Method Blast Radius Analysis
1. Locate target method (e.g. `PaymentGateway.processTransaction`).
2. Click **`⬆ Callers`** in the Right Inspector.
3. Set **Scan Depth Slider** to `3` or `5`.
4. The canvas renders all public API controllers, background schedulers, and services that depend directly or transitively on this method.

### Field-to-Method Mutation Propagation Chains
1. Select any mutable field (e.g. `Account.accountBalance`).
2. Click **`🔗 Propagation Chain`**.
3. CodeLens traverses:
   $$\text{Field} \longleftarrow \text{Direct Writer Methods} \longleftarrow \text{Upstream Calling Triggers}$$
4. Color-coded roles:
   - 🟡 **Field** (Target state variable)
   - 🔴 **Direct Writer** (Methods mutating the variable)
   - 🔵 **Calling Trigger** (Upstream entry points invoking the writers)
   - 🟢 **Direct Reader** (Methods accessing the variable)

---

## Data Storage, H2 Compaction & High-Scale Persistence

All database files and search indices are stored locally in `./codelens-data/`:

```
codelens-data/
├── codelens_db.mv.db       # Compressed Embedded H2 Database (LZF compression, auto-compact)
├── codelens_db.trace.db    # H2 transaction trace log
└── lucene-index/           # Apache Lucene index directory shards
```

### High-Scale Performance Architecture (30k–50k+ Java Files)
- **High-Throughput Bulk Ingestion**: Drops secondary B-tree indexes and disables undo logging during full scans to achieve constant $O(1)$ batch insertion speeds regardless of existing table size.
- **LZF Page Compression & Compaction**: H2 runs with `COMPRESS=TRUE` and `AUTO_COMPACT_FILL_RATE=50`. Post-scan and shutdown triggers execute `SHUTDOWN COMPACT` to eliminate MVStore dead page fragments, keeping 30k-file repositories under 1–2 GB on disk (instead of 40GB+ uncompacted).
- **Leak-Free Connection Management**: All database connections operate under strict try-with-resources blocks with explicit `setAutoCommit(false)` chunk commits, preventing connection pool exhaustion and HikariCP leak warnings.
- **Graceful Shutdown & Interrupted Scan Recovery**: Process termination or API shutdown requests cleanly flush Lucene index writers and H2 connection pools. Interrupted scans are safely flagged upon startup and can be resumed with a single click.

> [!TIP]
> **Resetting the Database**: To completely reset your indexed data, simply delete the `./codelens-data/` folder and initiate a fresh scan from the web interface.

---

## Comprehensive REST API Reference

All endpoints return JSON and are accessible locally at `http://localhost:7878/api`.

### Scan & Lifecycle Controls
- `POST /api/scan` — Start a background full scan (`{"sourcePath": "/path/to/src", "excludePatterns": ["target", "build"]}`)
- `POST /api/scan/incremental` — Start an incremental delta scan (`{"sourcePath": "/path/to/src", "excludePatterns": [...]}`)
- `POST /api/scan/cancel` — Cooperatively cancel an active scan in progress
- `GET /api/scan/status` — Poll current scan progress, phases, entity counts, and status
- `GET /api/scan/changes` — Inspect detected filesystem changes (new, modified, deleted files)
- `GET /api/scan/browse` — Open native OS directory chooser dialog
- `POST /api/shutdown` — Gracefully stop the CodeLens server process and flush storage
- `GET /api/stats` — Summary entity counts (`types`, `methods`, `fields`, `relationships`, `inconsistencies`)

### Packages & Types
- `GET /api/packages` — Flat list of indexed packages
- `GET /api/packages/{fqn}/types` — Types declared in a specific package
- `GET /api/types` — List all types (supports `?q=` query and pagination)
- `GET /api/types/{fqn}` — Detail for a type including member fields, methods, and Git telemetry

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

### Analyst Notes & Documentation
- `GET /api/notes/{entityFqn}` — Retrieve analyst notes for an entity
- `POST /api/notes` — Create or update an analyst note (`{"entityFqn": "...", "content": "..."}`)
- `DELETE /api/notes/{id}` — Delete an analyst note
- `GET /api/readme` — Retrieve this full comprehensive markdown guide from the server

---

## Keyboard Shortcuts

| Shortcut | Action | Scope |
|---|---|---|
| `⌘K` / `Ctrl+K` | Focus global Lucene entity search | Global |
| `Esc` | Clear search query / Close active modal dialog / Deselect node | Global |
| `1` | Switch to Graph Canvas view | Global |
| `2` | Switch to Knowledge Base Catalog view | Global |
| `3` | Switch to Code Review & Logic Auditor view | Global |
| `4` | Switch to Git Analytics & Churn view | Global |
| `Space` | Toggle Force Physics simulation (Freeze / Unfreeze) | Graph Tab |
| `H` | Toggle Git Churn Heatmap overlay | Graph Tab |
| `F` | Fit entire graph to screen (Auto-Zoom) | Graph Tab |
| `+` / `-` | Zoom in / Zoom out on canvas | Graph Tab |
| `0` | Reset zoom to 100% | Graph Tab |
| `[` | Toggle Left Explorer panel visibility | Global |
| `]` | Toggle Right Inspector panel visibility | Global |

---

## Troubleshooting & FAQ

### 1. The database file grew too large on previous scans
- **Solution**: Run a fresh scan in CodeLens 1.0.6+. The newly implemented LZF page compression and automated post-scan compaction (`SHUTDOWN COMPACT`) will shrink the `.mv.db` file from 40GB+ down to 1–2 GB.

### 2. Java Heap Out of Memory during 100k+ Class Scans
- **Solution**: Increase max heap allocation:
  ```bash
  java -Xms2g -Xmx6g -XX:+UseG1GC -jar codelens.jar
  ```

### 3. Port 7878 is already in use
- **Solution**: Pass `-Dcodelens.port=PORT` on startup:
  ```bash
  java -Dcodelens.port=8080 -jar codelens.jar
  ```

### 4. How do I completely wipe all indexed data?
- **Solution**: Shut down the server, delete the `./codelens-data` directory, and restart.

---

<p align="center">
  <b>CodeLens — Offline Java Architectural Intelligence</b><br/>
  <i>Zero telemetry · Zero cloud dependencies · 100% Local</i>
</p>
