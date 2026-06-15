# CodeLens — Java Codebase Intelligence Tool

A fully **offline**, self-contained Java codebase analysis and visualisation
platform. Scan any Java source tree, then explore packages, classes, methods,
and fields through an interactive dark-theme web UI served locally.

---

## Five Capabilities

| # | Capability | How it works |
|---|-----------|--------------|
| 1 | **Source Index** | JavaParser AST visitor walks every `.java` file and indexes packages, types, fields, and methods with full metadata into an embedded H2 database |
| 2 | **Call Hierarchy** | BFS over an in-memory call graph (built from CALLS relationships) shows callers and callees up to N hops for any selected method |
| 3 | **Field Impact** | For any field, shows every method that reads it, writes it, or propagates its value downstream |
| 4 | **Knowledge Base** | Every entity has a detail page with location, modifiers, cyclomatic complexity, class hierarchy, analyst notes, and relationship list |
| 5 | **Inconsistency Detection** | Three-pass detector flags divergent signatures for same-named methods, similar-named entities with structural drift, and duplicate body hashes |

---

## Architecture

```
codelens/
├── codelens-core/        Domain model (CodeType, CodeMethod, CodeField, …)
├── codelens-parser/      JavaParser AST visitor + directory scanner
├── codelens-analysis/    Call graph BFS, field impact, inconsistency detector
├── codelens-storage/     H2 DAOs + Apache Lucene full-text search index
├── codelens-api/         Javalin REST server (all HTTP routes)
├── codelens-web/         Vanilla JS SPA + custom Canvas force-graph renderer
├── codelens-app/         Fat-JAR assembly + main entry point
└── sample-project/       Five-class trading system — ready-made scan target
```

### Library Decisions (ADRs)

| Layer | Library | Rationale |
|-------|---------|-----------|
| Java AST parsing | **JavaParser 3.25.8** | Best offline Java 17 parser; no classpath resolution needed |
| Embedded SQL | **H2 2.2.224** | File-mode, zero-server, instant start, full JDBC |
| Full-text search | **Apache Lucene 9.10.0** | Best-in-class embedded search; file-based index |
| Graph algorithms | **Pure Java BFS** (no JGraphT) | BFS/reversed-BFS fits the problem exactly; zero extra deps |
| HTTP server | **Javalin 6.1.3** | Minimal embedded Jetty; lowest ceremony REST DSL |
| JSON | **Jackson 2.17.0** | Industry standard; Javalin integration built-in |
| Connection pool | **HikariCP 5.1.0** | Fastest JDBC pool; needed for concurrent scan+query |
| Logging | **Logback 1.5.3** | SLF4J backend; colour console output |
| Graph rendering | **Custom Canvas 2D** | Fully offline; Verlet-integrated force simulation |

---

## Prerequisites

- **Java 17+** (`java -version`)
- **Maven 3.8+** (`mvn -version`)

---

## Build

```bash
# From the project root (where this README lives)
cd codelens
mvn package -q
```

The fat JAR is written to:
```
codelens-app/target/codelens-app-1.0.0.jar
```

---

## Run

```bash
java -jar codelens-app/target/codelens-app-1.0.0.jar
```

Then open your browser at **http://localhost:7878**

### Optional JVM flags

```bash
# Custom port
java -Dcodelens.port=9090 -jar codelens-app/target/codelens-app-1.0.0.jar

# Custom data directory (where H2 + Lucene files are stored)
java -Dcodelens.data=/tmp/my-index -jar codelens-app/target/codelens-app-1.0.0.jar
```

---

## Scanning a Project

### Option A — Web UI
1. Open **http://localhost:7878**
2. Paste the **absolute path** to your Java source root into the top bar
   (e.g. `/Users/you/myproject/src/main/java`)
3. Click **Scan**
4. Watch the progress bar; the explorer auto-refreshes when done

### Option B — REST
```bash
curl -X POST http://localhost:7878/api/scan \
     -H 'Content-Type: application/json' \
     -d '{"sourcePath":"/Users/you/myproject/src/main/java"}'
```

### Sample project (included)
```bash
# Get the absolute path
realpath sample-project/src/main/java

# Paste that path into the UI scan box
```

---

## REST API Quick Reference

```
GET  /api/stats                     Entity counts
GET  /api/packages                  All packages (for the tree)
GET  /api/packages/{fqn}/types      Types in a package
GET  /api/types                     All types  (?q= for search)
GET  /api/types/{fqn}               Type detail + fields + methods
GET  /api/methods/{fqn}             Method detail
GET  /api/methods/{fqn}/graph       Call hierarchy graph  (?depth=3)
GET  /api/methods/{fqn}/callers     Caller list
GET  /api/methods/{fqn}/callees     Callee list
GET  /api/fields/{fqn}              Field detail
GET  /api/fields/{fqn}/impact       Field impact graph
GET  /api/inconsistencies           All detected inconsistencies
GET  /api/search?q=placeOrder       Full-text search (Lucene)
GET  /api/notes/{fqn}               Analyst notes for an entity
POST /api/notes                     Save a note  {entityFqn, content}
DELETE /api/notes/{id}              Delete a note
POST /api/scan                      Start scan   {sourcePath}
GET  /api/scan/status               Scan progress
```

---

## UI Guide

| Panel | Purpose |
|-------|---------|
| **Left — Explorer** | Package tree; click a package to expand it and see types; filter chips (Class / Iface / Enum) narrow the list |
| **Left — Search** | Lucene-backed full-text search across all entities; ⌘K to focus |
| **Centre — Graph** | Interactive force-directed canvas; drag nodes, scroll to zoom, click a node to load its detail |
| **Centre — Knowledge Base** | Structured list view of all types and their members |
| **Centre — Inconsistencies** | Flagged issues sorted by similarity score |
| **Right — Detail** | Selected entity metadata, cyclomatic complexity bar, relationships, and free-text analyst notes |

### Keyboard shortcuts

| Key | Action |
|-----|--------|
| `⌘K` / `Ctrl+K` | Focus search |
| `Esc` | Clear search |
| `1` | Switch to Graph tab |
| `2` | Switch to Knowledge Base tab |
| `3` | Switch to Inconsistencies tab |

---

## Data Storage

All index data is stored in the **data directory** (default: `./codelens-data/`):

```
codelens-data/
├── codelens_db.mv.db    H2 database file (packages, types, fields, methods, …)
└── lucene-index/        Apache Lucene search index shards
```

To **reset** the index, simply delete the `codelens-data/` directory and re-scan.

---

## Post-MVP Roadmap

| Phase | Feature |
|-------|---------|
| 2 | JavaParser SymbolSolver for fully-resolved type-aware call edges |
| 2 | Source code viewer pane (syntax-highlighted, jump-to-line) |
| 3 | Git blame integration — annotate entities with last-change author |
| 3 | Deep structural diff (AST-level comparison, not just body-hash) |
| 4 | Multi-project workspace — scan and cross-reference several repos |
| 4 | Export knowledge base to HTML/PDF report |
| 5 | Plugin API — user-supplied analysis passes as JAR drop-ins |

---

## Project Layout (full)

```
codelens/
│
├── pom.xml                          Parent POM — dependency management
│
├── codelens-core/                   Zero-dep domain model
│   └── src/main/java/com/codelens/core/model/
│       ├── CodePackage.java
│       ├── CodeType.java
│       ├── CodeField.java
│       ├── CodeMethod.java
│       ├── MethodParam.java
│       ├── CodeRelationship.java
│       ├── AnalystNote.java
│       ├── InconsistencyReport.java
│       └── ScanProgress.java
│
├── codelens-parser/                 JavaParser integration
│   └── src/main/java/com/codelens/parser/
│       ├── AstVisitor.java          VoidVisitorAdapter → entities + relationships
│       └── JavaSourceScanner.java   Directory walker + parse orchestrator
│
├── codelens-analysis/               Graph analysis (no external graph lib)
│   └── src/main/java/com/codelens/analysis/
│       ├── CallGraphAnalyzer.java   In-memory BFS call graph
│       ├── FieldImpactAnalyzer.java Field read/write/propagate analysis
│       └── InconsistencyDetector.java Three-pass structural inconsistency detection
│
├── codelens-storage/                H2 + Lucene persistence
│   └── src/main/java/com/codelens/storage/
│       ├── DatabaseManager.java     H2 lifecycle + DDL
│       ├── EntityDao.java           Consolidated CRUD DAO
│       └── LuceneService.java       Full-text index build + search
│
├── codelens-api/                    Javalin HTTP server
│   └── src/main/java/com/codelens/api/
│       └── CodeLensServer.java      All REST routes + scan orchestration
│
├── codelens-web/                    Frontend (zero JS dependencies)
│   └── src/main/resources/web/
│       ├── index.html               Three-panel layout shell
│       ├── style.css                Dark theme design system
│       ├── graph.js                 ForceGraph — Canvas 2D + Verlet physics
│       └── app.js                   Application controller + API client
│
├── codelens-app/                    Fat JAR assembly
│   └── src/main/java/com/codelens/app/
│       └── Application.java         main() — wires and starts everything
│
└── sample-project/                  Ready-to-scan demo codebase
    └── src/main/java/com/example/trading/
        ├── MarketDataFeed.java
        ├── Portfolio.java
        ├── RiskEngine.java
        ├── TradeProcessor.java
        ├── OrderService.java
        └── TradingSystemBootstrap.java
```
