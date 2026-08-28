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
import java.nio.charset.StandardCharsets;
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
 * POST /api/review                → on-demand code review (file, snippet, or entity)
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
    private final CodeReviewEngine   codeReviewEngine;
    private final ReportService      reportService;
    private final GitBlameService    gitBlameService;
    private final int                port;

    // ── Scan state (updated by background thread, read by poll endpoint) ──────
    private final AtomicReference<ScanProgress> scanState =
        new AtomicReference<>(new ScanProgress(ScanProgress.Status.IDLE));
    private final AtomicReference<GitAnalysisProgress> gitProgress =
        new AtomicReference<>(new GitAnalysisProgress(GitAnalysisProgress.Status.IDLE));
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
        this.codeReviewEngine      = new CodeReviewEngine();
        this.reportService         = new ReportService(this.callGraph, this.fieldImpact, this.codeReviewEngine);
        this.gitBlameService       = new GitBlameService();
        this.port                  = port;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Start / Stop
    // ─────────────────────────────────────────────────────────────────────────

    public void start() {
        app = Javalin.create(cfg -> {
            // Serve static frontend files (live from disk in dev workspace, fallback to JAR classpath)
            java.io.File localWeb = new java.io.File("codelens-web/src/main/resources/web");
            if (localWeb.isDirectory()) {
                cfg.staticFiles.add(localWeb.getAbsolutePath(), Location.EXTERNAL);
            } else {
                cfg.staticFiles.add("/web", Location.CLASSPATH);
            }
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
        app.get("/api/graph/all",            this::getFullGraph);
        app.get("/api/graph/architecture",   this::getArchitectureGraph);
        app.get("/api/graph/dsm",            this::getDSM);
        app.get("/api/graph/treemap",        this::getTreemap);

        // ── Fields ────────────────────────────────────────────────────────────
        app.get("/api/fields/{id}",          this::getField);
        app.get("/api/fields/{id}/impact",   this::getFieldImpact);

        // ── Code Review ───────────────────────────────────────────────────────
        app.post("/api/review",              this::reviewCode);

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
        app.post("/api/git/validate",        this::validateGitRepo);
        app.post("/api/git/analyze",         this::analyzeGit);
        app.get("/api/git/status",           this::getGitStatus);

        // ── Reports & Exports ─────────────────────────────────────────────────
        app.get("/api/reports/architecture",  this::getArchitectureReport);
        app.get("/api/reports/review",        this::getReviewReport);
        app.get("/api/reports/metrics",       this::getMetricsReport);
        app.get("/api/reports/html-snapshot", this::getHtmlSnapshotReport);
        app.get("/api/reports/download",      this::downloadReport);

        // ── Configuration & Deployment Settings (.conf) ──────────────────────
        app.get("/api/config",          this::getConfig);
        app.post("/api/config",         this::saveConfig);
        app.get("/api/config/export",   this::exportConfig);
        app.post("/api/config/import",  this::importConfig);
        app.post("/api/config/reset",   this::resetConfig);

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
    // Body: { "sourcePath": "/absolute/path/to/src", "excludePatterns": ["target", "build", "..."] }
    // ─────────────────────────────────────────────────────────────────────────
    private void startScan(Context ctx) {
        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        String sourcePath = (String) body.get("sourcePath");
        if (sourcePath == null || sourcePath.isBlank()) {
            ctx.status(400).json(Map.of("error", "sourcePath is required"));
            return;
        }

        Object rawExcludes = body.get("excludePatterns");
        List<String> excludePatterns = null;
        if (rawExcludes instanceof List<?>) {
            excludePatterns = ((List<?>) rawExcludes).stream().map(Object::toString).toList();
        } else if (rawExcludes instanceof String && !((String) rawExcludes).isBlank()) {
            excludePatterns = Arrays.stream(((String) rawExcludes).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
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
        final List<String> finalExcludes = excludePatterns;
        scanExecutor.submit(() -> runScan(sourcePath, finalExcludes, progress));

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
    private void runScan(String sourcePath, List<String> excludePatterns, ScanProgress progress) {
        try {
            // Phase 1: parse sources
            progress.setCurrentPhase("AST Parsing");
            progress.setMessage("Scanning Java source files…");
            JavaSourceScanner scanner = new JavaSourceScanner();
            JavaSourceScanner.ScanResult result = scanner.scan(
                sourcePath,
                excludePatterns,
                (done, total, file) -> {
                    progress.setTotalFiles(total);
                    progress.setProcessedFiles(done);
                    String fileName = file;
                    try {
                        fileName = java.nio.file.Paths.get(file).getFileName().toString();
                    } catch (Exception ignored) {}
                    progress.setCurrentDetail(fileName);
                    progress.setMessage(String.format("Parsing %s (%d/%d)", fileName, done, total));
                });

            // Phase 2: persist to H2
            progress.setCurrentPhase("Database Storage");
            progress.setMessage("Persisting AST & relationships to database…");
            progress.setCurrentDetail(String.format("%d types · %d methods · %d fields · %d rels",
                result.types.size(), result.methods.size(), result.fields.size(), result.relationships.size()));
            db.clearAll();
            dao.batchInsertPackages(result.packages);
            dao.batchInsertTypes(result.types);
            dao.batchInsertFields(result.fields);
            dao.batchInsertMethods(result.methods);
            dao.batchInsertRelationships(result.relationships);

            // Phase 3: rebuild Lucene index
            progress.setCurrentPhase("Lucene Indexing");
            progress.setMessage("Rebuilding full-text search index…");
            progress.setCurrentDetail("Indexing " + (result.types.size() + result.methods.size() + result.fields.size()) + " symbols");
            lucene.rebuildIndex(result.types, result.methods, result.fields);

            // Phase 4: rebuild in-memory call graph
            progress.setCurrentPhase("Graph Analysis");
            progress.setMessage("Computing call graph & field propagation…");
            List<String> allMethodFqns = dao.findAllMethodFqns();
            List<CodeRelationship> allRels = dao.findAllRelationships();
            progress.setCurrentDetail(String.format("Analyzing %d call paths", allRels.size()));
            callGraph.rebuild(allMethodFqns, allRels);
            fieldImpact.rebuild(allRels);

            // Main scan finishes immediately after graph and indexing
            progress.setTypesFound(result.types.size());
            progress.setMethodsFound(result.methods.size());
            progress.setFieldsFound(result.fields.size());
            progress.setRelationshipsFound(result.relationships.size());
            progress.setCurrentPhase("Complete");
            progress.setCurrentDetail("Ready");
            progress.setEndTime(System.currentTimeMillis());
            progress.setStatus(ScanProgress.Status.COMPLETE);
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
        Map<String, Object> stats = dao.getStats();
        stats.put("methodsList", dao.findMethodSignatures());
        stats.put("typesList", dao.findTypeSignatures());
        ctx.json(stats);
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
        detail.put("packageFqn", type.isPresent() ? type.get().getPackageFqn() : "");
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

    private void getFullGraph(Context ctx) throws Exception {
        ctx.json(callGraph.fullGraphView());
    }

    private void getArchitectureGraph(Context ctx) throws Exception {
        String scope  = ctx.queryParam("scope");
        String filter = ctx.queryParam("filter");
        ctx.json(callGraph.architectureGraphView(scope, filter));
    }

    private void getDSM(Context ctx) throws Exception {
        String scope  = ctx.queryParam("scope");
        String filter = ctx.queryParam("filter");
        ctx.json(callGraph.dsmView(scope, filter));
    }

    private void getTreemap(Context ctx) throws Exception {
        String scope  = ctx.queryParam("scope");
        String filter = ctx.queryParam("filter");
        List<CodeType> types = dao.findAllTypes();
        List<CodeMethod> methods = dao.findAllMethods();

        List<CallGraphAnalyzer.TreemapTypeRecord> typeRecs = new java.util.ArrayList<>();
        for (CodeType t : types) {
            typeRecs.add(new CallGraphAnalyzer.TreemapTypeRecord(
                t.getFqn(), t.getSimpleName(), t.getPackageFqn(), t.getKind(), t.getLineCount()));
        }

        List<CallGraphAnalyzer.TreemapMethodRecord> methodRecs = new java.util.ArrayList<>();
        for (CodeMethod m : methods) {
            methodRecs.add(new CallGraphAnalyzer.TreemapMethodRecord(
                m.getFqn(), m.getSimpleName(), m.getDeclaringTypeFqn(),
                m.getStartLine(), m.getEndLine(), m.getCyclomaticComplexity()));
        }

        ctx.json(CallGraphAnalyzer.treemapView(typeRecs, methodRecs, scope, filter));
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
        detail.put("packageFqn", type.isPresent() ? type.get().getPackageFqn() : "");
        ctx.json(detail);
    }

    private void getFieldImpact(Context ctx) throws Exception {
        String id    = decode(ctx.pathParam("id"));
        int    depth = intParam(ctx, "depth", 1);
        ctx.json(fieldImpact.analyse(id, depth, callGraph));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Code Review (on-demand)
    // Body: { "filePath": "..." } or { "snippet": "..." } or { "entityFqn": "..." }
    // ─────────────────────────────────────────────────────────────────────────
    private void reviewCode(Context ctx) {
        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        String filePath  = (String) body.get("filePath");
        String snippet   = (String) body.get("snippet");
        String entityFqn = (String) body.get("entityFqn");

        List<ReviewFinding> findings;

        if (snippet != null && !snippet.isBlank()) {
            // Review a pasted code snippet
            findings = codeReviewEngine.reviewSnippet(snippet, callGraph, fieldImpact);
        } else if (filePath != null && !filePath.isBlank()) {
            // Review a specific file
            findings = codeReviewEngine.reviewFile(filePath.trim(), callGraph, fieldImpact);
        } else if (entityFqn != null && !entityFqn.isBlank()) {
            // Review by entity FQN — look up its source file
            try {
                String sourceFile = null;
                Optional<CodeType> typeOpt = dao.findTypeById(entityFqn);
                if (typeOpt.isPresent()) {
                    sourceFile = typeOpt.get().getSourceFile();
                } else {
                    // Try to find it as a method's declaring type
                    Optional<CodeMethod> methodOpt = dao.findMethodById(entityFqn);
                    if (methodOpt.isPresent()) {
                        String declaringType = methodOpt.get().getDeclaringTypeFqn();
                        if (declaringType != null) {
                            Optional<CodeType> parentType = dao.findTypeById(declaringType);
                            if (parentType.isPresent()) {
                                sourceFile = parentType.get().getSourceFile();
                            }
                        }
                    }
                }
                if (sourceFile != null && !sourceFile.isBlank()) {
                    findings = codeReviewEngine.reviewFile(sourceFile, callGraph, fieldImpact);
                } else {
                    ctx.status(404).json(Map.of("error", "Source file not found for entity: " + entityFqn));
                    return;
                }
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Review failed: " + e.getMessage()));
                return;
            }
        } else {
            ctx.status(400).json(Map.of("error", "Provide filePath, snippet, or entityFqn"));
            return;
        }

        ctx.json(findings);
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
            ctx.status(200).json(Map.of("found", false));
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
        summary.put("hotEntities", dao.findHottestEntities(500));
        ctx.json(summary);
    }

    /**
     * POST /api/git/validate
     * Body: { "repoPath": "..." }
     * Validates if the path is a valid Git repository root.
     */
    private void validateGitRepo(Context ctx) {
        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        String repoPath = (String) body.get("repoPath");
        GitRepoLocator.ValidationResult result = GitRepoLocator.validate(repoPath);
        if (result.isValid()) {
            ctx.json(Map.of(
                "valid", true,
                "repoPath", result.getRepoPath(),
                "branch", result.getBranch(),
                "headCommit", result.getHeadCommit() != null ? result.getHeadCommit() : ""
            ));
        } else {
            ctx.json(Map.of(
                "valid", false,
                "error", result.getError() != null ? result.getError() : "Invalid Git repository"
            ));
        }
    }

    /**
     * POST /api/git/analyze
     * Body: { "repoPath": "..." }
     * Triggers asynchronous background Git blame & churn analysis.
     */
    private void analyzeGit(Context ctx) {
        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        String repoPath = (String) body.get("repoPath");

        GitRepoLocator.ValidationResult validation = GitRepoLocator.validate(repoPath);
        if (!validation.isValid()) {
            ctx.status(400).json(Map.of("error", validation.getError()));
            return;
        }

        GitAnalysisProgress current = gitProgress.get();
        if (current != null && current.getStatus() == GitAnalysisProgress.Status.RUNNING) {
            ctx.status(409).json(Map.of("error", "Git analysis already in progress", "progress", current));
            return;
        }

        String canonicalRepoPath = validation.getRepoPath();
        GitAnalysisProgress initial = new GitAnalysisProgress(GitAnalysisProgress.Status.RUNNING);
        initial.setRepoPath(canonicalRepoPath);
        initial.setBranch(validation.getBranch());
        initial.setStartTime(System.currentTimeMillis());
        initial.setMessage("Preparing Git history analysis…");
        gitProgress.set(initial);

        CompletableFuture.runAsync(() -> {
            try {
                List<CodeType> types = dao.findAllTypes();
                List<CodeMethod> methods = dao.findAllMethods();
                List<CodeField> fields = dao.findAllFields();

                GitBlameService.ScanResult gitResult = new GitBlameService.ScanResult(types, methods, fields);
                File repoRoot = new File(canonicalRepoPath);

                List<GitMeta> gitMetas = gitBlameService.annotate(gitResult, repoRoot, (done, total, curFile) -> {
                    GitAnalysisProgress p = gitProgress.get();
                    if (p != null) {
                        p.setProcessedFiles(done);
                        p.setTotalFiles(total);
                        p.setCurrentFile(curFile);
                        p.setMessage(String.format("Auditing Git blame %d/%d files (%s)", done, total, curFile));
                    }
                });

                dao.batchInsertGitMeta(gitMetas);
                log.info("Background Git analysis completed: {} entities annotated", gitMetas.size());

                GitAnalysisProgress completed = gitProgress.get();
                if (completed != null) {
                    completed.setStatus(GitAnalysisProgress.Status.COMPLETE);
                    completed.setEntitiesAnnotated(gitMetas.size());
                    completed.setEndTime(System.currentTimeMillis());
                    completed.setMessage("Git analysis complete — " + gitMetas.size() + " entities annotated.");
                }
            } catch (Exception e) {
                log.error("Background Git analysis failed", e);
                GitAnalysisProgress errorP = gitProgress.get();
                if (errorP != null) {
                    errorP.setStatus(GitAnalysisProgress.Status.ERROR);
                    errorP.setErrorDetail(e.getMessage());
                    errorP.setMessage("Git analysis failed: " + e.getMessage());
                    errorP.setEndTime(System.currentTimeMillis());
                }
            }
        });

        ctx.json(Map.of("status", "started", "repoPath", canonicalRepoPath, "branch", validation.getBranch()));
    }

    /**
     * GET /api/git/status
     * Returns current background Git analysis status.
     */
    private void getGitStatus(Context ctx) {
        ctx.json(gitProgress.get());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Folder navigation / Reveal
    // ─────────────────────────────────────────────────────────────────────────

    private void browseFolder(Context ctx) {
        String current = ctx.queryParam("current");
        String os = System.getProperty("os.name", "").toLowerCase();

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            // 1. Try Windows native PowerShell FolderBrowserDialog
            if (os.contains("win")) {
                try {
                    String initialPath = (current != null && !current.trim().isEmpty()) ? current.trim() : "";
                    String psScript = String.format(
                        "Add-Type -AssemblyName System.Windows.Forms; " +
                        "$dialog = New-Object System.Windows.Forms.FolderBrowserDialog; " +
                        "$dialog.Description = 'Select Java Source Folder'; " +
                        "$dialog.ShowNewFolderButton = $false; " +
                        (initialPath.isEmpty() ? "" : "$dialog.SelectedPath = '" + initialPath.replace("'", "''") + "'; ") +
                        "if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $dialog.SelectedPath } else { Write-Output '' }"
                    );
                    Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psScript).start();
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                        String selected = reader.readLine();
                        process.waitFor(3, java.util.concurrent.TimeUnit.MINUTES);
                        if (selected != null && !selected.trim().isEmpty()) {
                            return selected.trim();
                        }
                    }
                } catch (Exception e) {
                    log.warn("PowerShell folder picker failed: {}", e.getMessage());
                }
            }

            // 2. Try AWT / Swing JFileChooser if display is available
            if (!GraphicsEnvironment.isHeadless()) {
                try {
                    CompletableFuture<String> swingFuture = new CompletableFuture<>();
                    SwingUtilities.invokeLater(() -> {
                        try {
                            try {
                                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                            } catch (Exception ignored) {}

                            JFileChooser chooser = new JFileChooser();
                            chooser.setDialogTitle("Select Java Source Folder");
                            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                            if (current != null && !current.trim().isEmpty()) {
                                File f = new File(current.trim());
                                if (f.exists() && f.isDirectory()) {
                                    chooser.setCurrentDirectory(f);
                                }
                            }
                            int result = chooser.showOpenDialog(null);
                            if (result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                                swingFuture.complete(chooser.getSelectedFile().getAbsolutePath());
                            } else {
                                swingFuture.complete("");
                            }
                        } catch (Exception ex) {
                            swingFuture.complete("");
                        }
                    });
                    return swingFuture.get(2, java.util.concurrent.TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.warn("Swing folder picker failed: {}", e.getMessage());
                }
            }

            return "";
        });

        ctx.future(() -> future.thenAccept(path -> ctx.json(Map.of("path", path != null ? path : ""))));
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
            ctx.status(404).json(Map.of("error", "File not found: " + path));
            return;
        }

        try {
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
        if (!file.exists()) {
            ctx.status(404).json(Map.of("error", "File not found: " + path));
            return;
        }

        try {
            java.nio.file.Files.writeString(file.toPath(), content);
            ctx.json(Map.of("success", true, "path", path));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to write file: " + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reports & Exports
    // ─────────────────────────────────────────────────────────────────────────

    private void getArchitectureReport(Context ctx) {
        try {
            String format = ctx.queryParam("format");
            if (format == null || format.isBlank()) format = "markdown";
            else format = format.trim().toLowerCase();

            List<CodeType> types = dao.findAllTypes();
            List<CodeMethod> methods = dao.findAllMethods();
            List<CodeField> fields = dao.findAllFields();
            List<CodeRelationship> rels = dao.findAllRelationships();

            ReportService.ArchitectureReportData data = reportService.buildArchitectureData(types, methods, fields, rels);

            if ("html".equals(format)) {
                ctx.contentType("text/html; charset=UTF-8").result(reportService.renderArchitectureHtml(data));
            } else if ("json".equals(format)) {
                ctx.json(data);
            } else {
                ctx.contentType("text/markdown; charset=UTF-8").result(reportService.renderArchitectureMarkdown(data));
            }
        } catch (Exception e) {
            log.error("Failed to generate architecture report: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to generate architecture report: " + e.getMessage()));
        }
    }

    private void getReviewReport(Context ctx) {
        try {
            String format = ctx.queryParam("format");
            if (format == null || format.isBlank()) format = "markdown";
            else format = format.trim().toLowerCase();

            List<CodeType> types = dao.findAllTypes();
            ReportService.ReviewReportData data = reportService.buildReviewReportData(types);

            if ("html".equals(format)) {
                ctx.contentType("text/html; charset=UTF-8").result(reportService.renderReviewHtml(data));
            } else if ("json".equals(format)) {
                ctx.json(data);
            } else if ("csv".equals(format)) {
                ctx.contentType("text/csv; charset=UTF-8").result(reportService.renderReviewCsv(data));
            } else {
                ctx.contentType("text/markdown; charset=UTF-8").result(reportService.renderReviewMarkdown(data));
            }
        } catch (Exception e) {
            log.error("Failed to generate review report: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to generate review report: " + e.getMessage()));
        }
    }

    private void getMetricsReport(Context ctx) {
        try {
            String format = ctx.queryParam("format");
            if (format == null || format.isBlank()) format = "csv";
            else format = format.trim().toLowerCase();

            List<CodeType> types = dao.findAllTypes();
            List<CodeMethod> methods = dao.findAllMethods();
            List<CodeField> fields = dao.findAllFields();
            ReportService.MetricsReportData data = reportService.buildMetricsData(types, methods, fields);

            if ("html".equals(format)) {
                ctx.contentType("text/html; charset=UTF-8").result(reportService.renderMetricsHtml(data));
            } else if ("json".equals(format)) {
                ctx.json(data);
            } else if ("markdown".equals(format) || "md".equals(format)) {
                ctx.contentType("text/markdown; charset=UTF-8").result(reportService.renderMetricsMarkdown(data));
            } else {
                ctx.contentType("text/csv; charset=UTF-8").result(reportService.renderMetricsCsv(data));
            }
        } catch (Exception e) {
            log.error("Failed to generate metrics report: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to generate metrics report: " + e.getMessage()));
        }
    }

    private void getHtmlSnapshotReport(Context ctx) {
        try {
            String scope = ctx.queryParam("scope");
            String filter = ctx.queryParam("filter");

            List<CodeType> types = dao.findAllTypes();
            List<CodeMethod> methods = dao.findAllMethods();
            List<CodeField> fields = dao.findAllFields();
            List<CodeRelationship> rels = dao.findAllRelationships();

            ReportService.ArchitectureReportData archData = reportService.buildArchitectureData(types, methods, fields, rels);
            Object fullGraph = callGraph.fullGraphView();
            Object archGraph = callGraph.architectureGraphView(scope, filter);

            String projectName = types.isEmpty() ? "Codebase"
                : (types.get(0).getPackageFqn() != null && !types.get(0).getPackageFqn().isBlank() ? types.get(0).getPackageFqn() : "Codebase");

            String html = reportService.generateInteractiveHtmlSnapshot(projectName, fullGraph, archGraph, archData);
            ctx.contentType("text/html; charset=UTF-8").result(html);
        } catch (Exception e) {
            log.error("Failed to generate HTML graph snapshot: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to generate HTML graph snapshot: " + e.getMessage()));
        }
    }

    private void downloadReport(Context ctx) {
        try {
            String type = ctx.queryParam("type");
            if (type == null || type.isBlank()) type = "architecture";
            else type = type.trim().toLowerCase();

            String format = ctx.queryParam("format");
            if (format == null || format.isBlank()) format = "markdown";
            else format = format.trim().toLowerCase();

            if ("html-snapshot".equals(type) || "graph-snapshot".equals(type)) {
                ctx.header("Content-Disposition", "attachment; filename=\"codelens-interactive-graph.html\"");
                getHtmlSnapshotReport(ctx);
                return;
            }

            String ext = format.equals("markdown") ? "md" : format;
            String filename = "codelens-" + type + "-report." + ext;
            ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");

            if ("review".equals(type)) {
                getReviewReport(ctx);
            } else if ("metrics".equals(type)) {
                getMetricsReport(ctx);
            } else {
                getArchitectureReport(ctx);
            }
        } catch (Exception e) {
            log.error("Failed to download report: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to download report: " + e.getMessage()));
        }
    }

    // ── Configuration & Deployment Management ──────────────────────────────────
    private CodeLensConfig activeConfig;
    private File activeConfigFile;

    public void setConfig(CodeLensConfig config, File configFile) {
        this.activeConfig = config;
        this.activeConfigFile = configFile;
    }

    public CodeLensConfig getActiveConfig() {
        if (activeConfig == null) {
            activeConfig = new CodeLensConfig();
        }
        return activeConfig;
    }

    private void getConfig(Context ctx) {
        ctx.json(getActiveConfig());
    }

    private void saveConfig(Context ctx) {
        try {
            CodeLensConfig updated = ctx.bodyAsClass(CodeLensConfig.class);
            this.activeConfig = updated;
            File targetFile = activeConfigFile != null ? activeConfigFile : new File("./codelens.conf");
            activeConfig.saveToFile(targetFile);
            log.info("Persisted configuration to {}", targetFile.getAbsolutePath());
            ctx.json(Map.of("status", "ok", "message", "Configuration saved successfully", "config", activeConfig));
        } catch (Exception e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            ctx.status(400).json(Map.of("error", "Failed to save configuration: " + e.getMessage()));
        }
    }

    private void exportConfig(Context ctx) {
        String conf = getActiveConfig().toConfString();
        ctx.contentType("text/plain; charset=utf-8")
           .header("Content-Disposition", "attachment; filename=\"codelens.conf\"")
           .result(conf);
    }

    private void importConfig(Context ctx) {
        try {
            String confContent;
            var uploadedFile = ctx.uploadedFile("file");
            if (uploadedFile != null) {
                confContent = new String(uploadedFile.content().readAllBytes(), StandardCharsets.UTF_8);
            } else {
                confContent = ctx.body();
            }

            if (confContent == null || confContent.isBlank()) {
                ctx.status(400).json(Map.of("error", "No configuration content provided"));
                return;
            }

            CodeLensConfig imported = CodeLensConfig.fromConfString(confContent);
            this.activeConfig = imported;
            File targetFile = activeConfigFile != null ? activeConfigFile : new File("./codelens.conf");
            activeConfig.saveToFile(targetFile);
            log.info("Successfully imported and saved configuration from .conf to {}", targetFile.getAbsolutePath());
            ctx.json(Map.of("status", "ok", "message", "Configuration imported and restored successfully", "config", activeConfig));
        } catch (Exception e) {
            log.error("Failed to import configuration: {}", e.getMessage(), e);
            ctx.status(400).json(Map.of("error", "Failed to parse/import configuration: " + e.getMessage()));
        }
    }

    private void resetConfig(Context ctx) {
        this.activeConfig = new CodeLensConfig();
        try {
            File targetFile = activeConfigFile != null ? activeConfigFile : new File("./codelens.conf");
            activeConfig.saveToFile(targetFile);
            ctx.json(Map.of("status", "ok", "message", "Configuration reset to factory defaults", "config", activeConfig));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to save reset configuration: " + e.getMessage()));
        }
    }
}

