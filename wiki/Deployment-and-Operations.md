# Deployment & Operations

CodeLens is designed for frictionless deployment across developer workstations, isolated air-gapped lab servers, and enterprise CI/CD environments.

---

## 🚀 Deployment Options

### Method 1: Lightweight Two-JAR Deployment (Recommended)

1. **Build the JARs**:
   ```bash
   ./mvnw clean package -DskipTests
   ```
2. **Copy to Target Machine**:
   Transfer both `codelens-app.jar` (~1.1 MB) and `codelens-deps.jar` (~22 MB) into the deployment directory:
   ```bash
   scp codelens-app/target/codelens-app.jar codelens-app/target/codelens-deps.jar user@server:/opt/codelens/
   ```
3. **Run on Target**:
   ```bash
   cd /opt/codelens && java -jar codelens-app.jar
   ```
   *Subsequent code updates only require transferring the tiny `codelens-app.jar` (~1.1 MB)!*

---

### Method 2: Docker Container Deployment

CodeLens can be run in an isolated Docker container with local directories mounted:

```dockerfile
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY codelens-app/target/codelens-app.jar /app/codelens-app.jar
COPY codelens-app/target/codelens-deps.jar /app/codelens-deps.jar
EXPOSE 7878
VOLUME ["/app/data", "/sources"]
ENTRYPOINT ["java", "-Dcodelens.data=/app/data", "-jar", "/app/codelens-app.jar"]
```

**Run Container**:
```bash
docker run -d \
  --name codelens \
  -p 7878:7878 \
  -v codelens_data:/app/data \
  -v /path/to/java/repo:/sources:ro \
  codelens:latest
```

---

### Method 3: Linux Production Systemd Service

To configure CodeLens as a background service on Ubuntu / Debian / RHEL:

1. Copy JARs to `/opt/codelens/`.
2. Create service file `/etc/systemd/system/codelens.service`:
   ```ini
   [Unit]
   Description=CodeLens Java Codebase Intelligence Server
   After=network.target

   [Service]
   Type=simple
   User=codelens
   WorkingDirectory=/opt/codelens
   ExecStart=/usr/bin/java -Xms1g -Xmx4g -XX:+UseG1GC -Dcodelens.port=7878 -Dcodelens.data=/var/lib/codelens -jar /opt/codelens/codelens-app.jar
   Restart=always
   RestartSec=5

   [Install]
   WantedBy=multi-user.target
   ```
3. Enable and start:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now codelens
   sudo systemctl status codelens
   ```

---

### Method 4: macOS LaunchAgent Daemon

To configure CodeLens to launch automatically at login on macOS:

1. Place the JAR at `~/Applications/CodeLens/codelens-app.jar`.
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
           <string>/Users/username/Applications/CodeLens/codelens-app.jar</string>
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

## ⚙️ JVM Memory & GC Tuning

For repositories containing 50,000 to 125,000+ classes, tune the JVM to leverage the G1 garbage collector:

```bash
java -Xms2g -Xmx6g \
     -XX:+UseG1GC \
     -XX:G1ReservePercent=15 \
     -XX:InitiatingHeapOccupancyPercent=45 \
     -Dcodelens.port=7878 \
     -jar codelens-app.jar
```

* `-Xms2g -Xmx6g`: Pre-allocates 2 GB initial heap with room to expand to 6 GB under load.
* `-XX:+UseG1GC`: Low-latency concurrent garbage collection prevents stop-the-world pauses during deep graph queries.
* `-XX:G1ReservePercent=15`: Reserves headroom to prevent allocation failures during bursty AST ingestion.

---

## 🗄️ Database Maintenance & Storage Compaction

All state is persisted in `./codelens-data/`:
* `codelens_db.mv.db`: Embedded H2 SQL database with LZF page compression.
* `lucene-index/`: Apache Lucene inverted text index shards.
* `graph-cache/`: Precomputed coordinates and serialized report artifacts.

### Compaction Mechanics:
* **Automatic Compaction**: H2 operates with `AUTO_COMPACT_FILL_RATE=50`.
* **Graceful Shutdown Compaction**: Calling `POST /api/shutdown` executes an H2 `SHUTDOWN COMPACT` statement, re-aligning MVStore pages and defragmenting free space.
* **Complete Reset**: To clear all indexed repositories, delete `./codelens-data/` while the server is stopped.
