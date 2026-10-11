# Developer CLI & Toolsuite

CodeLens includes a first-class headless **Command Line Interface (CLI)** that exposes the entire analytical power of the platform directly in developer terminals and CI/CD automation pipelines.

Executable: `java -jar codelens-app.jar [command] [options]` or using the bundled alias `codelens [command]`.

---

## 🚀 The Developer CLI Commands

```
codelens
  ├── scan       Run full or incremental 8-stage codebase analysis
  ├── story      Discover and inspect business transaction storylines
  ├── trace      Calculate shortest or critical call pathways between entities
  ├── what-if    Simulate upstream blast radius of modifying a class or method
  ├── ask        Execute architecture-grounded natural language AI queries
  ├── explain    Explain class archetype, responsibilities, and call graph
  ├── teach-me   Interactive terminal curriculum and onboarding walkthrough
  ├── pr-story   Generate Git diff blast radius and architectural PR narrative
  └── ai-test    Execute live external handshake verification against AI providers
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
# List all discovered transaction flows with step counts
codelens story

# Output full execution path and step-by-step trace for a specific flow ID
codelens story flow_order_checkout_4815
```

---

### 3. `trace` — Critical Call Pathway Tracing

Traces execution call chains between two methods or classes, calculating shortest path, hop counts, and transitive dependencies.

```bash
codelens trace com.example.api.OrderController.createOrder com.example.db.OrderDao.save
```

---

### 4. `what-if` — Blast Radius Simulator

Simulates the ripple effect of modifying a class or method signature before making changes.

```bash
codelens what-if com.example.service.PaymentGateway.processPayment
```

Outputs direct callers, transitive callers, affected REST controllers, impacted database tables, and estimated risk tier (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`).

---

### 5. `ask` — Architecture-Grounded AI Query

Queries the codebase using local or remote LLMs grounded directly by AST compilation units, the call graph, and database sinks.

```bash
# Ask an architectural question directly from terminal
codelens ask "How does order placement flow work, and what tables are modified?"

# Specify provider and model
codelens ask "Explain persistence architecture" --provider ollama --model qwen3-coder:latest
```

---

### 6. `explain` — Deep Entity Explanation

Generates an in-depth breakdown of a specific class or interface: archetype, architectural layer, afferent/efferent coupling, and incoming/outgoing call contracts.

```bash
codelens explain com.example.service.OrderValidationService
```

---

### 7. `teach-me` — Interactive Terminal Onboarding

Launches an interactive, step-by-step terminal curriculum for new developers onboarding to the codebase across 5 structured architectural layers:

```bash
codelens teach-me
```

---

### 8. `pr-story` — Git PR Change Story Generator

Analyzes git branch diffs against a base branch, evaluates structural impact, and outputs a formatted Markdown narrative ready for GitHub Pull Request descriptions.

```bash
# Run PR story script
scripts/codestory-pr.sh main feature/my-branch

# Or via headless CLI
codelens pr-story main feature/my-branch
```

---

### 9. `ai-test` — Live AI Provider Handshake Verification

Tests external connectivity and handshake validity for a specified AI provider:

```bash
# Test local Ollama service (checks port 11434 and model availability)
codelens ai-test --provider ollama --endpoint http://localhost:11434/api/generate

# Test OpenAI provider
codelens ai-test --provider openai --api-key sk-...
```

---

## ⚙️ Environment Variables & Configuration (`codelens.conf`)

| Variable / Key | Description | Default |
|---|---|---|
| `CODELENS_PORT` / `server.port` | HTTP port for web UI server | `7878` |
| `CODELENS_NO_BROWSER` | Prevent auto-opening web browser (`true`/`false`) | `false` |
| `ai.provider` | AI provider: `local`, `ollama`, `openai` | `local` |
| `ai.model` | Model name (e.g. `qwen3-coder:latest`, `llama3`, `gpt-4o-mini`) | `llama3` |
| `ai.endpoint` | HTTP endpoint URL for Ollama/OpenAI API | `http://localhost:11434/api/generate` |
| `ai.apiKey` | API authentication key for remote provider | `""` |
| `CODELENS_MAX_HEAP_MB` | Heap limit override before tripping Sentinel | Auto-detected |
