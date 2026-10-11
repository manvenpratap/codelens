# Codebase Intelligence Reports Suite

CodeLens includes an enterprise-grade reporting engine that synthesizes **13 deep architectural, risk, persistence, concurrency, and compliance reports**, generating **57 pre-rendered artifacts** (HTML, Markdown, CSV, and JSON) stored in `./codelens-data/graph-cache/reports/`.

---

## ⚡ Sequential Precomputation Engine

Reports are precomputed automatically post-scan or on-demand via `POST /api/reports/regenerate`. The precomputation runner executes strictly **sequentially (1 through 13)** with three key guarantees:

1. **Error Isolation**: Each report runs within an isolated `try-catch` block. If one analysis encounters incomplete AST data, subsequent reports continue uninterrupted.
2. **Per-Report Duration Tracking**: Execution timing is tracked and logged individually (e.g. `[REPORT 8/13] COMPLETED Code Quality & Security Audit in 927 ms`).
3. **Dedicated Terminal Banners**: Every report emits formatted `REPORT_BUILD_STARTED` and `REPORT_BUILD_COMPLETED` banners to standard out for complete auditability.

```text
╔════════════════════════════════════════════════════════════════════════════════════╗
║ CODELENS BACKGROUND PROCESS REPORT_BUILD_STARTED                                   ║
║ Process:   [10/13] API Surface & REST Endpoint Catalog                             ║
║ Target:    api-catalog                                                             ║
║ Details:   Computing analysis model and rendering artifacts                        ║
║ Timestamp: 2026-10-01 22:19:04.066                           Memory: 185MB / 512MB ║
╚════════════════════════════════════════════════════════════════════════════════════╝
...
[REPORT 10/13] COMPLETED API Surface & REST Endpoint Catalog in 17 ms
```

---

## 📊 Catalog of 13 Intelligence Reports

| # | Report Key | Report Title | Analytical Domain | Export Formats |
|---|---|---|---|---|
| 1 | `architecture` | Architecture & Coupling Report | Martin Modularity Metrics, $C_a$, $C_e$, Instability $I$ | HTML, MD, CSV, JSON |
| 2 | `change-risk` | Change Risk & Blast Radius Matrix | Behavioral Hotspots ($CC \times Churn \times LOC$) | HTML, MD, CSV, JSON |
| 3 | `dead-code` | Dead Code & Reachability Analysis | Uncalled methods & orphaned subsystems | HTML, MD, CSV, JSON |
| 4 | `circular-dependencies`| Circular Dependencies & Cycles | Tarjan strongly connected components / cycles | HTML, MD, CSV, JSON |
| 5 | `archetype-governance` | Enterprise Archetype Governance | Architectural layer & pattern compliance | HTML, MD, CSV, JSON |
| 6 | `technical-debt` | Technical Debt & SQALE ROI | SQALE remediation remediation hours and debt score | HTML, MD, CSV, JSON |
| 7 | `executive-summary` | Executive Architectural Scorecard | High-level health grades (A–F) and risk radar | HTML, MD, CSV, JSON |
| 8 | `review` | Code Quality & Security Audit | 32 AST static rule violations with line numbers | HTML, MD, CSV, JSON |
| 9 | `metrics` | Inventory & Metrics Census | Type, method, field, and LOC distributions | HTML, MD, CSV, JSON |
| 10 | `api-catalog` | API Surface & REST Endpoint Catalog | Routes, HTTP methods, controllers, blast radius | HTML, MD, CSV, JSON |
| 11 | `database-access` | Database & Data Access Flow | Persistence patterns, CRUD ops, target tables | HTML, MD, CSV, JSON |
| 12 | `concurrency-audit` | Concurrency & Thread Safety Audit | Locks, volatile state, shared collections | HTML, MD, CSV, JSON |
| 13 | `module-coupling` | Intermodular Class Usage Insights | Cross-module calls, touchpoints & leaks | HTML, MD, CSV, JSON |
| 14 | `html-snapshot` | Standalone Offline HTML Snapshot | Bundled single-file offline report bundle | Standalone HTML |

---

## 🔍 In-Depth Report Descriptions

### 1. Architecture & Coupling Report (`architecture`)
* **Focus**: Evaluates Robert C. Martin's Package Coupling Metrics across packages and modules.
* **Key Metrics**:
  * Afferent Coupling ($C_a$): Upstream dependents.
  * Efferent Coupling ($C_e$): Downstream dependencies.
  * Instability ($I = C_e / (C_a + C_e)$): Values near 0 denote stable packages; values near 1 denote volatile packages.
  * Abstractness ($A$): Ratio of interfaces/abstract classes to total types.
  * Distance from Main Sequence ($D = |A + I - 1|$): Normalized architectural drift indicator.

### 2. Change Risk & Blast Radius Matrix (`change-risk`)
* **Focus**: Combines AST structural complexity with JGit commit churn history.
* **Risk Formula**: $\text{Composite Risk} = CC \times \log_2(1 + \text{churn}) \times \log_{10}(\max(10, LOC))$.
* **Output**: Ranks the top 20 most volatile classes in the codebase and highlights methods that trigger the highest downstream blast radius upon modification.

### 3. Dead Code & Reachability Analysis (`dead-code`)
* **Focus**: Traverses the in-memory JGraphT directed call graph from public entry points (controllers, main methods, scheduled tasks, message listeners).
* **Output**: Identifies private and package-private methods with 0 incoming call edges, dead branches, and unreachable helper utilities eligible for deletion.

### 4. Circular Dependencies & Tangling (`circular-dependencies`)
* **Focus**: Applies Tarjan's strongly connected components algorithm to identify mutual cycles.
* **Output**: Categorizes cycles across Package-level and Class-level tangling, detailing cycle depth (2-hop mutual calls, 3-hop rings, or multi-hop knots) with remediation recommendations.

### 5. Enterprise Archetype Governance (`archetype-governance`)
* **Focus**: Verifies whether codebase entities conform to intended architectural archetypes (e.g. BaNCS Transactions vs Tasks, Spring Controllers vs Repositories, DDD Aggregates).
* **Output**: Compliance scores per module, unclassified entity counts, and cross-boundary violation warnings (e.g. a Controller directly instantiating a DAO bypassing Services).

### 6. Technical Debt & SQALE Remediation ROI (`technical-debt`)
* **Focus**: Quantifies technical debt using the SQALE (Software Quality Assessment based on Lifecycle Expectations) methodology.
* **Output**: Total remediation hours, estimated technical debt ratio (debt hours vs development hours), and high-ROI refactoring targets ranked by remediation effort vs risk reduction.

### 7. Executive Architectural Health Scorecard (`executive-summary`)
* **Focus**: C-suite and engineering management overview synthesizing architectural health into a single unified letter grade (**A** through **F**).
* **Output**: 5 high-level index gauges: Structural Modularity, Code Quality, Change Safety, Testability, and Persistence Integrity.

### 8. Code Quality & Security Audit (`review`)
* **Focus**: Runs all 32 AST static review rules across every class in the repository.
* **Output**: Categorized findings:
  * Null Pointer Risks (unverified returns, unchecked dereferences).
  * Concurrency Pitfalls (non-thread-safe collections, synchronized bottlenecks).
  * Exception Handling (empty catch blocks, swallowed exceptions, catching `Throwable`).
  * Performance Smells (string concatenation in loops, excessive allocation).

### 9. Codebase Inventory & Metrics Census (`metrics`)
* **Focus**: Comprehensive inventory breakdown across packages, types, methods, fields, and lines of code.
* **Output**: Distribution histograms of method length, cyclomatic complexity distributions, interface-to-implementation ratios, and member counts.

### 10. API Surface & REST Endpoint Catalog (`api-catalog`)
* **Focus**: Automatically detects and catalogs every REST and RPC boundary.
* **Key Features**:
  * Extracts HTTP methods (`GET`, `POST`, `PUT`, `DELETE`, `PATCH`).
  * Endpoint URL path patterns (e.g. `/api/orders/{id}`).
  * Controller handler class and method references.
  * Public vs Internal access permissions and authentication check presence.
  * Downstream call chain depth and blast radius impact score.

### 11. Database & Data Access Flow (`database-access`)
* **Focus**: Persistence architecture audit tracing data access from DAOs to SQL tables.
* **Key Features**:
  * Persistence Integrity Score (0–100): Evaluates transaction boundary discipline and prepared statement usage.
  * Total Data Access Classes (DAOs, Repositories, Storage managers).
  * CRUD Operation Distribution: Total read operations vs write mutations.
  * High-Touch Database Tables: Frequency ranking of database tables touched by queries.
  * Method-level touchpoint table detailing target tables, SQL patterns, and transaction status.

### 12. Concurrency & Thread Safety Audit (`concurrency-audit`)
* **Focus**: Multi-threading hazard analysis and thread safety validation.
* **Key Features**:
  * Thread Safety Letter Grade (**A** through **F**) and composite score.
  * Synchronized Monitor Count: Methods holding synchronization locks.
  * Volatile State Fields: Variable declarations enforcing memory barrier guarantees.
  * Concurrent Collections: Usage count of `ConcurrentHashMap`, `AtomicInteger`, etc.
  * Unsafe Mutable Collections: Detection of static mutable collections (`ArrayList`, `HashMap`) vulnerable to race conditions without synchronization.
  * Actionable remediation advice for synchronization bottlenecks and lock contention.

### 13. Intermodular Class Usage & Coupling Insights (`module-coupling`)
* **Focus**: Granular cross-boundary method invocation matrix and intermodular touchpoint analysis.
* **Key Features**:
  * Cross-Module Dependency Matrix: Interactive grid showing caller modules vs callee modules.
  * Shared Touchpoint Inspector: Drilldown into exact methods invoked across module boundaries.
  * Leakage & Boundary Violations: Flags unexpected cross-boundary calls that violate clean architecture layering.

### 14. Standalone Offline HTML Snapshot (`html-snapshot`)
* **Focus**: Portable, zero-dependency HTML file containing interactive SVG diagrams, charts, and report tables.
* **Output**: Generates a self-contained `.html` file that can be emailed or published to documentation portals without requiring CodeLens to be running.

---

## 🔌 Reports REST API

### Fetch Report Data
```http
GET /api/reports/:reportKey?format=json|html|markdown|csv
```

### Check Precomputation Progress
```http
GET /api/reports/status
```

### Trigger Full Regeneration
```http
POST /api/reports/regenerate
```

### Download Artifact File
```http
GET /api/reports/download?type=:reportKey&format=html|markdown|csv
```
