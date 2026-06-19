package com.codelens.api;

import com.codelens.analysis.*;
import com.codelens.core.model.*;
import com.codelens.git.GitBlameService;
import com.codelens.git.GitRepoLocator;
import com.codelens.parser.JavaSourceScanner;
import com.codelens.storage.*;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CodeLens HTTP server — mounts every REST endpoint and serves the
 * static frontend files from the classpath (/web/*).
 *
 * Port default: 7878  (override with -Dcodelens.port=NNNN)
 *
 * Route map
 * ─────────────────────────────────────────────────────────────────────
 * GET  /                          → redirect to /index.html
 * POST /api/scan                  → start background scan
 * GET  /api/scan/status           → poll scan progress
 * GET  /api/stats                 → entity counts
 *
 * GET  /api/packages              → all packages (tree-compatible list)
 * GET  /api/packages/{fqn}/types  → types in a package
 *
 * GET  /api/types                 → all types (paginated)
 * GET  /api/types/{id}            → type detail + fields + methods
 *
 * GET  /api/methods/{id}          → method detail
 * GET  /api/methods/{id}/callers  → caller tree (BFS, depth=4)
 * GET  /api/methods/{id}/callees  → callee tree (BFS, depth=4)
 * GET  /api/methods/{id}/graph    → full call hierarchy graph view
 *
 * GET  /api/fields/{id}           → field detail
 * GET  /api/fields/{id}/impact    → field impact analysis graph
 *
 * GET  /api/inconsistencies       → all detected inconsistencies
 *
 * GET  /api/search?q=             → full-text search (Lucene)
 *
 * GET  /api/notes/{entityFqn}     → notes for an entity
 * POST /api/notes                 → create/update a note  {entityFqn, content}
 * DELETE /api/notes/{id}          → delete a note
 * ─────────────────────────────────────────────────────────────────────
 */
public class CodeLensServer {

    private static final Logger log = LoggerFactory.getLogger(CodeLensServer.class);

    // ── Dependencies injected at construction ─────────────────────────────────
    private final DatabaseManager    db;
    private final LuceneService      lucene;
    private final EntityDao          dao;
    private final CallGraphAnalyzer  callGraph;
    private final FieldImpactAnalyzer fieldImpact;
    private final InconsistencyDetector inconsistencyDetector;
    private final GitBlameService    gitBlameService;
    private final int                port;

    // ── Scan state (updated by background thread, read by poll endpoint) ──────
    private final AtomicReference<ScanProgress> scanState =
        new AtomicReference<>(new ScanProgress(ScanProgress.Status.IDLE));
    private final ExecutorService scanExecutor =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "codelens-scanner");
            t.setDaemon(true);
            return t;
        });

    private Javalin app;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public CodeLensServer(DatabaseManager db, LuceneService lucene, int port) {
        this.db                    = db;
        this.lucene                = lucene;
        this.dao                   = new EntityDao(db);
        this.callGraph             = new CallGraphAnalyzer();
        this.fieldImpact           = new FieldImpactAnalyzer();
        this.inconsistencyDetector = new InconsistencyDetector();
        this.gitBlameService       = new GitBlameService();
        this.port                  = port;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Start / Stop
    // ─────────────────────────────────────────────────────────────────────────

    public void start() {
        app = Javalin.create(cfg -> {
            // Serve static frontend files from the JAR classpath under /web/
            cfg.staticFiles.add("/web", Location.CLASSPATH);
            cfg.jsonMapper(new JavalinJackson());
            // Allow all origins during local use (no cross-origin issues)
            cfg.bundledPlugins.enableCors(cors ->
                cors.addRule(rule -> rule.anyHost()));
        });

        // ── Root redirect ─────────────────────────────────────────────────────
        app.get("/", ctx -> ctx.redirect("/index.html"));

        // ── Scan ──────────────────────────────────────────────────────────────
        app.post("/api/scan",        this::startScan);
        app.get("/api/scan/status",  this::getScanStatus);
        app.get("/api/scan/browse",  this::browseFolder);
        app.post("/api/open-folder", this::openFolder);

        // ── Stats ─────────────────────────────────────────────────────────────
        app.get("/api/stats",        this::getStats);

        // ── Packages ──────────────────────────────────────────────────────────
        app.get("/api/packages",              this::listPackages);
        app.get("/api/packages/{fqn}/types",  this::typesByPackage);

        // ── Types ─────────────────────────────────────────────────────────────
        app.get("/api/types",     this::listTypes);
        app.get("/api/types/{id}", this::getType);

        // ── Methods ───────────────────────────────────────────────────────────
        app.get("/api/methods/{id}",         this::getMethod);
        app.get("/api/methods/{id}/callers", this::getCallers);
        app.get("/api/methods/{id}/callees", this::getCallees);
        app.get("/api/methods/{id}/graph",   this::getCallGraph);

        // ── Fields ────────────────────────────────────────────────────────────
        app.get("/api/fields/{id}",          this::getField);
        app.get("/api/fields/{id}/impact",   this::getFieldImpact);

        // ── Inconsistencies ───────────────────────────────────────────────────
        app.get("/api/inconsistencies",      this::listInconsistencies);

        // ── Search ────────────────────────────────────────────────────────────
        app.get("/api/search",               this::search);

        // ── Analyst notes ─────────────────────────────────────────────────────
        app.get("/api/notes/{entityFqn}",    this::getNotes);
        app.post("/api/notes",               this::saveNote);
        app.delete("/api/notes/{id}",        this::deleteNote);

        // ── Files ─────────────────────────────────────────────────────────────
        app.get("/api/files/read",           this::readFile);
        app.post("/api/files/write",         this::writeFile);

        // ── Git metadata ──────────────────────────────────────────────────────
        app.get("/api/git/meta/{entityFqn}", this::getGitMeta);
        app.get("/api/git/summary",          this::getGitSummary);

        // ── Global error handler ──────────────────────────────────────────────
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled error on {} {}: {}", ctx.method(), ctx.path(), e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        });

        // Build call graph from database on startup
        try {
            List<String> allMethodFqns = dao.findAllMethodFqns();
            List<CodeRelationship> allRels = dao.findAllRelationships();
            callGraph.rebuild(allMethodFqns, allRels);
            fieldImpact.rebuild(allRels);
            log.info("Initialized in-memory call graph from database with {} methods and {} relationships",
                allMethodFqns.size(), allRels.size());
        } catch (Exception e) {
            log.error("Failed to initialize call graph from database on startup: {}", e.getMessage(), e);
        }

        app.start(port);
        log.info("CodeLens server started on http://localhost:{}", port);
    }

    public void stop() {
        if (app != null) app.stop();
        scanExecutor.shutdownNow();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Handler: POST /api/scan
    // Body: { "sourcePath": "/absolute/path/to/src" }
    // ─────────────────────────────────────────────────────────────────────────
    private void startScan(Context ctx) {
        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        String sourcePath = (String) body.get("sourcePath");
        if (sourcePath == null || sourcePath.isBlank()) {
            ctx.status(400).json(Map.of("error", "sourcePath is required"));
            return;
        }

        // Reject if already running
        ScanProgress current = scanState.get();
        if (current.getStatus() == ScanProgress.Status.SCANNING) {
            ctx.status(409).json(Map.of("error", "Scan already in progress"));
            return;
        }

        // Initialise progress object
        ScanProgress progress = new ScanProgress(ScanProgress.Status.SCANNING);
        progress.setSourcePath(sourcePath);
        progress.setStartTime(System.currentTimeMillis());
        progress.setMessage("Initialising scanner…");
        scanState.set(progress);

        // Launch background scan task
        scanExecutor.submit(() -> runScan(sourcePath, progress));

        ctx.status(202).json(Map.of("status", "accepted", "sourcePath", sourcePath));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Handler: GET /api/scan/status
    // ─────────────────────────────────────────────────────────────────────────
    private void getScanStatus(Context ctx) {
        ctx.json(scanState.get());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Background scan task
    // ─────────────────────────────────────────────────────────────────────────
    private void runScan(String sourcePath, ScanProgress progress) {
        try {
            // Phase 1: parse sources
            progress.setMessage("Scanning Java sources…");
            JavaSourceScanner scanner = new JavaSourceScanner();
            JavaSourceScanner.ScanResult result = scanner.scan(
                sourcePath,
                (done, total, file) -> {
                    progress.setTotalFiles(total);
                    progress.setProcessedFiles(done);
                    progress.setMessage("Parsing file " + done + "/" + total);
                });

            // Phase 2: persist to H2
            progress.setMessage("Persisting index to database…");
            db.clearAll();
            dao.batchInsertPackages(result.packages);
            dao.batchInsertTypes(result.types);
            dao.batchInsertFields(result.fields);
            dao.batchInsertMethods(result.methods);
            dao.batchInsertRelationships(result.relationships);

            // Phase 3: rebuild Lucene index
            progress.setMessage("Rebuilding search index…");
            lucene.rebuildIndex(result.types, result.methods, result.fields);

            // Phase 4: rebuild in-memory call graph
            progress.setMessage("Building call graph…");
            List<String> allMethodFqns = dao.findAllMethodFqns();
            List<CodeRelationship> allRels = dao.findAllRelationships();
            callGraph.rebuild(allMethodFqns, allRels);
            fieldImpact.rebuild(allRels);

            // Phase 5: inconsistency detection
            progress.setMessage("Running inconsistency detection…");
            List<InconsistencyReport> issues =
                inconsistencyDetector.detect(result.methods, result.fields);
            dao.batchInsertInconsistencies(issues);

            // Phase 6: Git blame annotation (non-fatal if not a git repo)
            progress.setMessage("Running git blame annotation…");
            GitRepoLocator.locate(sourcePath).ifPresent(repoRoot -> {
                try {
                    GitBlameService.ScanResult gitResult = new GitBlameService.ScanResult(
                        result.types, result.methods, result.fields);
                    List<GitMeta> gitMetas = gitBlameService.annotate(gitResult, repoRoot);
                    dao.batchInsertGitMeta(gitMetas);
                    log.info("Git annotation: {} entities annotated", gitMetas.size());
                } catch (Exception e) {
                    log.warn("Git annotation phase failed (non-fatal): {}", e.getMessage());
                }
            });
            progress.setTypesFound(result.types.size());
            progress.setMethodsFound(result.methods.size());
            progress.setFieldsFound(result.fields.size());
            progress.setRelationshipsFound(result.relationships.size());
            progress.setEndTime(System.currentTimeMillis());
            progress.setStatus(ScanProgress.Status.COMPLETE);
            progress.setMessage("Scan complete — " + result.types.size() + " types indexed.");
            log.info("Scan finished: {}", progress.getMessage());

        } catch (Exception e) {
            log.error("Scan failed", e);
            progress.setStatus(ScanProgress.Status.ERROR);
            progress.setMessage("Scan failed");
            progress.setErrorDetail(e.getMessage());
            progress.setEndTime(System.currentTimeMillis());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────────────────────────
    private void getStats(Context ctx) throws Exception {
        ctx.json(dao.getStats());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Packages
    // ─────────────────────────────────────────────────────────────────────────
    private void listPackages(Context ctx) throws Exception {
        ctx.json(dao.findAllPackages());
    }

    private void typesByPackage(Context ctx) throws Exception {
        String fqn = ctx.pathParam("fqn");
        ctx.json(dao.findTypesByPackage(fqn));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Types
    // ─────────────────────────────────────────────────────────────────────────
    private void listTypes(Context ctx) throws Exception {
        String q = ctx.queryParam("q");
        if (q != null && !q.isBlank()) {
            ctx.json(dao.searchTypes(q));
        } else {
            ctx.json(dao.findAllTypes());
        }
    }

    private void getType(Context ctx) throws Exception {
        String id = decode(ctx.pathParam("id"));
        Optional<CodeType> type = dao.findTypeById(id);
        if (type.isEmpty()) { ctx.status(404).json(Map.of("error", "Not found")); return; }

        // Build rich response: type + fields + methods + notes
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type",    type.get());
        detail.put("fields",  dao.findFieldsByType(id));
        detail.put("methods", dao.findMethodsByType(id));
        detail.put("notes",   dao.findNotesByEntity(id));
        ctx.json(detail);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Methods
    // ─────────────────────────────────────────────────────────────────────────
    private void getMethod(Context ctx) throws Exception {
        String id = decode(ctx.pathParam("id"));
        Optional<CodeMethod> m = dao.findMethodById(id);
        if (m.isEmpty()) { ctx.status(404).json(Map.of("error", "Not found")); return; }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("method", m.get());
        detail.put("notes",  dao.findNotesByEntity(id));
        Optional<CodeType> type = dao.findTypeById(m.get().getDeclaringTypeFqn());
        detail.put("sourceFile", type.isPresent() ? type.get().getSourceFile() : "");
        ctx.json(detail);
    }

    private void getCallers(Context ctx) throws Exception {
        String id    = decode(ctx.pathParam("id"));
        int    depth = intParam(ctx, "depth", 4);
        ctx.json(callGraph.callersView(id, depth));
    }

    private void getCallees(Context ctx) throws Exception {
        String id    = decode(ctx.pathParam("id"));
        int    depth = intParam(ctx, "depth", 4);
        ctx.json(callGraph.calleesView(id, depth));
    }

    private void getCallGraph(Context ctx) throws Exception {
        String id    = decode(ctx.pathParam("id"));
        int    depth = intParam(ctx, "depth", 3);
        ctx.json(callGraph.callHierarchyView(id, depth));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fields
    // ─────────────────────────────────────────────────────────────────────────
    private void getField(Context ctx) throws Exception {
        String id = decode(ctx.pathParam("id"));
        Optional<CodeField> f = dao.findFieldById(id);
        if (f.isEmpty()) { ctx.status(404).json(Map.of("error", "Not found")); return; }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", f.get());
        detail.put("notes", dao.findNotesByEntity(id));
        Optional<CodeType> type = dao.findTypeById(f.get().getDeclaringTypeFqn());
        detail.put("sourceFile", type.isPresent() ? type.get().getSourceFile() : "");
        ctx.json(detail);
    }

    private void getFieldImpact(Context ctx) throws Exception {
        String id = decode(ctx.pathParam("id"));
        ctx.json(fieldImpact.analyse(id));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inconsistencies
    // ─────────────────────────────────────────────────────────────────────────
    private void listInconsistencies(Context ctx) throws Exception {
        ctx.json(dao.findAllInconsistencies());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Search
    // ─────────────────────────────────────────────────────────────────────────
    private void search(Context ctx) throws Exception {
        String q    = ctx.queryParam("q");
        int    hits = intParam(ctx, "limit", 30);
        if (q == null || q.isBlank()) {
            ctx.json(Collections.emptyList());
            return;
        }
        ctx.json(lucene.search(q, hits));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Analyst notes
    // ─────────────────────────────────────────────────────────────────────────
    private void getNotes(Context ctx) throws Exception {
        String entityFqn = decode(ctx.pathParam("entityFqn"));
        ctx.json(dao.findNotesByEntity(entityFqn));
    }

    private void saveNote(Context ctx) throws Exception {
        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        String entityFqn = (String) body.get("entityFqn");
        String content   = (String) body.get("content");
        String noteId    = (String) body.get("id");   // present for updates

        if (entityFqn == null || content == null) {
            ctx.status(400).json(Map.of("error", "entityFqn and content are required"));
            return;
        }

        AnalystNote note = new AnalystNote();
        note.setId(noteId != null ? noteId : UUID.randomUUID().toString());
        note.setEntityFqn(entityFqn);
        note.setContent(content);
        long now = System.currentTimeMillis();
        note.setCreatedAt(now);
        note.setUpdatedAt(now);
        dao.upsertNote(note);
        ctx.status(201).json(note);
    }

    private void deleteNote(Context ctx) throws Exception {
        String id = ctx.pathParam("id");
        boolean deleted = dao.deleteNote(id);
        if (!deleted) ctx.status(404).json(Map.of("error", "Note not found"));
        else ctx.json(Map.of("deleted", true));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** URL-decode a path parameter that may contain dots or special chars. */
    private String decode(String param) {
        try { return java.net.URLDecoder.decode(param, "UTF-8"); }
        catch (Exception e) { return param; }
    }

    /** Parse an integer query param, returning {@code defaultVal} on failure. */
    private int intParam(Context ctx, String name, int defaultVal) {
        try { return Integer.parseInt(ctx.queryParam(name)); }
        catch (Exception e) { return defaultVal; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Git metadata
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/git/meta/{entityFqn}
     * Returns git blame metadata for a single entity.
     * Returns 404 when the entity has no git annotation (not a git repo, or
     * entity not yet scanned).
     */
    private void getGitMeta(Context ctx) throws Exception {
        String entityFqn = decode(ctx.pathParam("entityFqn"));
        var meta = dao.findGitMetaByEntity(entityFqn);
        if (meta.isEmpty()) {
            ctx.status(404).json(Map.of("error", "No git metadata for entity"));
            return;
        }
        ctx.json(meta.get());
    }

    /**
     * GET /api/git/summary
     * Returns aggregate git statistics:
     *   · topAuthors   – top 10 committers by entity count
     *   · hotEntities  – top 20 most-changed entities (highest commit_count)
     */
    private void getGitSummary(Context ctx) throws Exception {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("topAuthors",  dao.findTopAuthors(10));
        summary.put("hotEntities", dao.findHottestEntities(20));
        ctx.json(summary);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Folder navigation / Reveal
    // ─────────────────────────────────────────────────────────────────────────

    private void browseFolder(Context ctx) {
        if (GraphicsEnvironment.isHeadless()) {
            ctx.status(400).json(Map.of("error", "Graphics environment is headless. Please type or paste the path manually."));
            return;
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {}

                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Select Java Source Folder");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                
                String current = ctx.queryParam("current");
                if (current != null && !current.trim().isEmpty()) {
                    File f = new File(current.trim());
                    if (f.exists() && f.isDirectory()) {
                        chooser.setCurrentDirectory(f);
                    }
                }

                int result = chooser.showOpenDialog(null);
                if (result == JFileChooser.APPROVE_OPTION) {
                    future.complete(chooser.getSelectedFile().getAbsolutePath());
                } else {
                    future.complete("");
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        ctx.future(() -> future.thenAccept(path -> ctx.json(Map.of("path", path))));
    }

    private void openFolder(Context ctx) throws Exception {
        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        String path = (String) body.get("path");
        if (path == null || path.trim().isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing path"));
            return;
        }

        File file = new File(path.trim());
        if (!file.exists()) {
            ctx.status(404).json(Map.of("error", "File or folder not found"));
            return;
        }

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            Runtime.getRuntime().exec(new String[]{"open", "-R", file.getAbsolutePath()});
        } else if (os.contains("win")) {
            Runtime.getRuntime().exec(new String[]{"explorer.exe", "/select,", file.getAbsolutePath()});
        } else {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file.getParentFile());
            } else {
                ctx.status(500).json(Map.of("error", "Desktop action not supported on this platform"));
                return;
            }
        }
        ctx.json(Map.of("success", true));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File reading / writing for Monaco Editor
    // ─────────────────────────────────────────────────────────────────────────

    private void readFile(Context ctx) {
        String path = ctx.queryParam("path");
        if (path == null || path.isBlank()) {
            ctx.status(400).json(Map.of("error", "path parameter is required"));
            return;
        }

        File file = new File(path.trim());
        if (!file.exists() || !file.isFile()) {
            ctx.status(404).json(Map.of("error", "File not found"));
            return;
        }

        // Security check: must reside inside current scanned sourcePath
        ScanProgress progress = scanState.get();
        String scannedPath = progress != null ? progress.getSourcePath() : null;
        if (scannedPath == null || scannedPath.isBlank()) {
            ctx.status(403).json(Map.of("error", "Access denied: no scan active"));
            return;
        }

        try {
            String canonicalFile = file.getCanonicalPath();
            String canonicalScan = new File(scannedPath.trim()).getCanonicalPath();
            if (!canonicalFile.startsWith(canonicalScan)) {
                ctx.status(403).json(Map.of("error", "Access denied: outside scanned path"));
                return;
            }

            String content = java.nio.file.Files.readString(file.toPath());
            ctx.json(Map.of("path", path, "content", content));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to read file: " + e.getMessage()));
        }
    }

    private void writeFile(Context ctx) {
        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        String path = (String) body.get("path");
        String content = (String) body.get("content");

        if (path == null || path.isBlank() || content == null) {
            ctx.status(400).json(Map.of("error", "path and content are required"));
            return;
        }

        File file = new File(path.trim());

        // Security check: must reside inside current scanned sourcePath
        ScanProgress progress = scanState.get();
        String scannedPath = progress != null ? progress.getSourcePath() : null;
        if (scannedPath == null || scannedPath.isBlank()) {
            ctx.status(403).json(Map.of("error", "Access denied: no scan active"));
            return;
        }

        try {
            String canonicalFile = file.getCanonicalPath();
            String canonicalScan = new File(scannedPath.trim()).getCanonicalPath();
            if (!canonicalFile.startsWith(canonicalScan)) {
                ctx.status(403).json(Map.of("error", "Access denied: outside scanned path"));
                return;
            }

            java.nio.file.Files.writeString(file.toPath(), content);
            ctx.json(Map.of("success", true, "path", path));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to write file: " + e.getMessage()));
        }
    }
}

