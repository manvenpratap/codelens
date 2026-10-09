# Developer CLI & Toolsuite

CodeLens includes a first-class headless **Command Line Interface (CLI)** that exposes the entire analytical power of the engine directly in developer terminals and CI/CD automation pipelines.

Executable: `java -jar codelens-app.jar [command] [options]` or using the bundled alias `codelens [command]`.

---

## 🚀 The 8 Developer CLI Commands

```
codelens
  ├── scan       Run full or incremental 8-stage codebase analysis
  ├── story      Discover and inspect business transaction storylines
  ├── trace      Calculate shortest or critical call pathways between entities
  ├── what-if    Simulate upstream blast-radius of modifying a class/method
  ├── ask        Execute architecture-grounded natural language AI queries
  ├── explain    Explain class archetype, responsibilities, and call graph
  ├── teach-me   Interactive terminal curriculum and onboarding walkthrough
  └── pr-story   Generate Git diff blast radius and architectural PR narrative
```

---

### 1. `scan` — Headless 8-Stage Codebase Scan

Executes the deterministic 8-stage analysis pipeline without launching the graphical web browser.

```bash
# Run full scan and launch local web server
codelens scan /path/to/project

# Run headless scan with SARIF report export for GitHub Code Scanning
codelens scan /path/to/project --no-browser --format sarif --output audit.sarif

# Run fast incremental delta scan
codelens scan /path/to/project --incremental
```

| Flag | Description | Default |
|---|---|---|
| `--incremental` | Detects file hash deltas and skips unchanged classes | `false` |
| `--format` | Output export format: `text`, `json`, `sarif`, or `html` | `text` |
| `--output`, `-o` | Destination file path for generated report | `stdout` |
| `--no-browser` | Disables automatic browser launch upon completion | `false` |
| `--port`, `-p` | Local HTTP port for web GUI server | `7878` |

---

### 2. `story` — Transaction Storyline Inspector

Inspects business transaction flows from API ingress controllers to database repositories.

```bash
# List all discovered transaction flows with step counts and confidence scores
codelens story

# Output full execution path and step-by-step trace for a specific flow ID
codelens story flow_order_checkout_4815
```

**Example Terminal Output:**
```
[Flow: flow_order_checkout_4815] POST /api/orders/checkout (Confidence: 0.94)
  Step 1: OrderController.checkout() [@RestController]
    ↳ calls OrderService.processOrder()
  Step 2: OrderService.processOrder() [@Service]
    ↳ calls PaymentClient.chargeCard()
    ↳ calls InventoryService.reserveStock()
    ↳ calls OrderRepository.save()
  Step 3: OrderRepository.save() [@Repository]
    ↳ SINK: Database INSERT into 'orders'
```

---

### 3. `trace` — Call Path Tracing

Finds the shortest or all viable call paths between two classes or methods in the directed call graph.

```bash
# Find call path between two classes
codelens trace com.example.OrderController com.example.PaymentGateway

# Find call path between specific methods
codelens trace com.example.OrderController#submit com.example.AuditLogger#log
```

---

### 4. `what-if` / `impact` — Blast Radius Simulator

Calculates the transitive upstream blast radius if a given class or method is modified, refactored, or removed.

```bash
# Simulate impact of modifying PaymentService
codelens what-if com.example.service.PaymentService

# Alias: impact
codelens impact com.example.repository.UserRepository
```

Outputs the direct callers, transitive callers, affected REST controllers, impacted database tables, and estimated risk tier (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`).

---

### 5. `ask` — Architecture-Grounded AI Query

Queries the codebase using local or remote LLMs grounded directly by the Lucene index, call graph, and structural integrity audit.

```bash
# Ask an architectural question directly from terminal
codelens ask "Where are user passwords hashed, and which database tables store auth credentials?"
```

---

### 6. `explain` — Deep Entity Explanation

Generates an in-depth breakdown of a specific class or interface: archetype, architectural layer, afferent/efferent coupling, and incoming/outgoing call contracts.

```bash
codelens explain com.example.service.OrderValidationService
```

---

### 7. `teach-me` — Interactive Terminal Onboarding

Launches an interactive, step-by-step terminal curriculum for new developers onboarding to the codebase:
* **Module 1**: High-Level System Architecture & Layering
* **Module 2**: Core Business Transaction Flows
* **Module 3**: Database Entities & Storage Topology
* **Module 4**: External Downstream Integrations & Clients
* **Module 5**: Known Tech Debt & High-Risk Areas

```bash
codelens teach-me
```

---

### 8. `pr-story` — Git Diff Blast Radius & PR Storyteller

Analyzes git branch diffs against a base branch, evaluates structural impact, and outputs a formatted Markdown narrative ready for GitHub Pull Request descriptions.

```bash
# Compare current working branch against main
codelens pr-story --base origin/main --head HEAD

# Output formatted Markdown summary
codelens pr-story --base v2.1.0 --head v2.2.0 --output PR_DESCRIPTION.md
```

**Generated PR Narrative Elements:**
* **Executive Summary**: Core functional changes and touched architectural subsystems.
* **Blast Radius Matrix**: Transitive callers affected by modified method signatures.
* **Structural Integrity Checks**: Verifies if the diff introduced any of the 14 class rule violations.
* **Test Gap Warnings**: Modified methods lacking direct or indirect unit test coverage.

---

## ⚙️ Environment Variables & Configuration

| Variable | Description | Default |
|---|---|---|
| `CODELENS_PORT` | HTTP port for web UI server | `7878` |
| `CODELENS_NO_BROWSER` | Prevent auto-opening web browser (`true`/`false`) | `false` |
| `CODELENS_DB_PATH` | Path to embedded H2 database file | `~/.codelens/codelens.db` |
| `AI_PROVIDER` | LLM backend: `anthropic`, `openai`, `gemini`, `ollama` | `none` |
| `AI_API_KEY` | API authentication key for selected provider | `""` |
| `AI_MODEL` | Custom model identifier override | Provider default |
| `CODELENS_MAX_HEAP_MB` | Heap limit override before tripping Sentinel | Auto-detected |
