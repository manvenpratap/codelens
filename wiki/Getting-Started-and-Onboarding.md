# Getting Started & Onboarding

This guide covers everything you need to set up, build, and operate CodeLens on your local workstation or enterprise server.

---

## 📋 System Prerequisites

CodeLens is built with modern Java and requires:
* **Java Runtime**: **JDK / JRE 17 or higher** (`java -version`).
* **Operating System**: macOS (Intel & Apple Silicon), Linux (Ubuntu, Debian, RHEL, CentOS, Arch), or Windows 10/11.
* **Hardware Requirements**:
  * Minimum: 2 CPU cores, 1 GB available RAM, 500 MB disk space.
  * Recommended (50k+ classes): 4+ CPU cores, 4–8 GB available RAM, SSD storage.

---

## 🔒 Zero-Admin Execution (100% User Space)

CodeLens was engineered specifically for corporate and security-restricted environments:
* **No Root / Sudo / Administrator Permissions Required**: Runs completely as an unprivileged user process.
* **Unprivileged Port Binding**: Binds to port `7878` by default (any port `>1024` does not require superuser elevation).
* **Local Data Confinement**: Persists its embedded H2 database, Lucene inverted index, and cache files strictly to `./codelens-data/` in the working directory (or a custom path via `-Dcodelens.data=...`), never touching system directories (`/etc`, `/var`, `C:\Program Files`).
* **Portable JRE Support**: On locked-down enterprise Windows machines without an installed JDK, download an archive build of [Eclipse Temurin 17 JRE](https://adoptium.net/), unpack it into a personal folder (e.g. `C:\Users\username\jre17`), and launch CodeLens directly:
  ```powershell
  C:\Users\username\jre17\bin\java.exe -jar codelens-app.jar
  ```

---

## 📦 Building the Modular Two-JAR Distribution

CodeLens uses an optimized two-JAR architecture:
1. `codelens-app.jar` (~1.1 MB): Application logic, REST endpoints, HTML/CSS/JS web assets, and analysis algorithms.
2. `codelens-deps.jar` (~22 MB): Pre-extracted third-party dependencies (Javalin, Jetty, Jackson, Lucene, JavaParser, JGit, H2, HikariCP, JGraphT).

### Build Command:
```bash
# macOS / Linux
./mvnw clean package -DskipTests

# Windows Command Prompt / PowerShell
.\mvnw.cmd clean package -DskipTests
```

> **Why Two JARs?** In enterprise development, third-party libraries change infrequently. By isolating dependencies into `codelens-deps.jar`, incremental feature updates to `codelens-app.jar` compile and package in **~3 to 4 seconds**, enabling rapid development cycles.

---

## 🚀 Launching CodeLens

### Default Startup
```bash
java -jar codelens-app/target/codelens-app.jar
```
Once the ASCII banner displays, navigate to:
**`http://localhost:7878`**

### Custom Configuration & Port Overrides
```bash
# Specify a custom HTTP port
java -Dcodelens.port=9090 -jar codelens-app/target/codelens-app.jar

# Specify a custom data directory for H2 and Lucene
java -Dcodelens.data=/data/codelens-store -jar codelens-app/target/codelens-app.jar

# Production JVM flags for massive codebases (50k–125k classes)
java -Xms2g -Xmx6g \
     -XX:+UseG1GC \
     -XX:G1ReservePercent=15 \
     -XX:InitiatingHeapOccupancyPercent=45 \
     -Dcodelens.port=7878 \
     -jar codelens-app/target/codelens-app.jar
```

---

## 💻 Headless CLI Scan Mode

For CI/CD pipelines, nightly cron jobs, or batch processing where a web server is not needed, CodeLens can be executed in headless mode:

```bash
# Scan a specific directory
java -jar codelens-app/target/codelens-app.jar scan /path/to/java/project

# Scan with custom data persistence directory
java -Dcodelens.data=/opt/codelens-data -jar codelens-app/target/codelens-app.jar scan /opt/workspace/src
```

### Headless Execution Phases:
1. AST parsing and entity extraction via JavaParser.
2. Batch relational persistence into embedded H2 database with drop/rebuild index optimization.
3. Lucene full-text inverted index building.
4. JGraphT directed call graph topology construction.
5. Sunflower spiral and 2D layout coordinates precomputation.
6. Sequential precomputation of all 13 architecture and risk reports.
7. Clean H2 compaction and process exit.

---

## 🧭 Navigating the Web Interface

Once connected to `http://localhost:7878`:
1. **Hero Landing Screen**: Enter or browse to an absolute directory containing `.java` source files and click **Scan**.
2. **5 Core Tabs**:
   - `Graph (1)`: Interactive force-directed canvas with call chains, blast radius, and field impact propagation.
   - `Knowledge Base (2)`: Package/class hierarchy, method signatures, line numbers, and cyclomatic complexity rankings.
   - `Code Review (3)`: 32-rule AST static code quality and security auditor.
   - `Git Analytics (4)`: Commit heat, author leaderboards, and code churn correlation.
   - `Source Editor (5)`: Integrated Microsoft Monaco code editor with syntax highlighting and direct disk-save capabilities.
3. **Top Action Buttons**:
   - `3D Studio (M)`: WebGL Software City & 3D Force Galaxy.
   - `Tasks`: Background process manager displaying all 11 active subsystems.
   - `Reports (R)`: 13-category intelligence reports hub with CSV, HTML, Markdown, and PDF exports.
   - `⌘K`: Global full-text search across all packages, classes, and methods.
