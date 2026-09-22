package com.codelens.api;

import com.codelens.analysis.*;
import com.codelens.core.ExcludedScope;
import com.codelens.core.model.*;
import com.codelens.git.GitBlameService;
import com.codelens.git.GitRepoLocator;
import com.codelens.parser.JavaSourceScanner;
import com.codelens.storage.*;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
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
    private final CriticalPathAnalyzer criticalPathAnalyzer;
    private final GitBlameService    gitBlameService;
    private final int                port;

    // ── Scan state (updated by background thread, read by poll endpoint) ──────
    private final AtomicReference<ScanProgress> scanState =
        new AtomicReference<>(new ScanProgress(ScanProgress.Status.IDLE));
    private final AtomicReference<GitAnalysisProgress> gitProgress =
        new AtomicReference<>(new GitAnalysisProgress(GitAnalysisProgress.Status.IDLE));
    private volatile boolean cancelRequested = false;
    private volatile List<String> lastExcludePatterns = Collections.emptyList();
    private final ExecutorService scanExecutor =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "codelens-scanner");
            t.setDaemon(true);
            return t;
        });

    private Javalin app;

    // ── Graph Layout Cache & Disk Persistence ────────────────────────────────
    private final Map<String, CallGraphAnalyzer.GraphView> layoutCache = new ConcurrentHashMap<>();
    private final ModuleDependencyAnalyzer moduleDependencyAnalyzer;
    private final Map<String, ModuleDependencyAnalyzer.ModuleDependencyInsights> moduleDependencyCache = new ConcurrentHashMap<>();
    private volatile ModuleDependencyAnalyzer.ModuleOverviewPayload cachedModuleOverview = null;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final AtomicLong scanRevision = new AtomicLong(System.currentTimeMillis());
    private final java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
    private final ApiTracker apiTracker = new ApiTracker();

    // ── Dedicated lifecycle tracking for background engines (independent of scanState) ──
    private final java.util.concurrent.atomic.AtomicBoolean graphWarmupRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicReference<String> graphWarmupPhase = new java.util.concurrent.atomic.AtomicReference<>("Ready");
    private final java.util.concurrent.atomic.AtomicInteger graphWarmupPercentage = new java.util.concurrent.atomic.AtomicInteger(0);

    private final java.util.concurrent.atomic.AtomicBoolean layoutWarmupRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicReference<String> layoutWarmupPhase = new java.util.concurrent.atomic.AtomicReference<>("Ready");
    private final java.util.concurrent.atomic.AtomicInteger layoutWarmupPercentage = new java.util.concurrent.atomic.AtomicInteger(0);

    public boolean handleConditionalETag(Context ctx, String cacheKey) {
        long rev = this.scanRevision.get();
        long keyHash;
        synchronized (crc32) {
            crc32.reset();
            crc32.update(cacheKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            keyHash = crc32.getValue();
        }
        String etag = "W/\"" + rev + "-" + Long.toHexString(keyHash) + "\"";

        ctx.header("ETag", etag);
        ctx.header("Cache-Control", "private, no-cache, must-revalidate");

        String ifNoneMatch = ctx.header("If-None-Match");
        if (ifNoneMatch != null && ifNoneMatch.trim().equals(etag)) {
            ctx.status(304);
            return true;
        }
        return false;
    }

    public File getGraphCacheDir() {
        String dataDir = getActiveConfig().getDataDir();
        if (dataDir == null || dataDir.isBlank()) dataDir = "./codelens-data";
        File dir = new File(dataDir, "graph-cache");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private String sanitizeCacheKey(String key) {
        if (key == null) return "null";
        return key.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    public CallGraphAnalyzer.GraphView getOrComputeLayout(String cacheKey, java.util.function.Supplier<CallGraphAnalyzer.GraphView> computer) {
        if (cacheKey == null) return computer.get();

        // 1. Check in-memory cache
        CallGraphAnalyzer.GraphView cached = layoutCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 2. Check disk cache
        File cacheFile = new File(getGraphCacheDir(), sanitizeCacheKey(cacheKey) + ".json");
        if (cacheFile.exists() && cacheFile.length() > 2) {
            try {
                CallGraphAnalyzer.GraphView diskView = jsonMapper.readValue(cacheFile, CallGraphAnalyzer.GraphView.class);
                if (diskView != null && diskView.nodes != null) {
                    layoutCache.put(cacheKey, diskView);
                    return diskView;
                }
            } catch (Exception e) {
                log.warn("Failed to read graph layout cache from {}: {}", cacheFile.getName(), e.getMessage());
            }
        }

        // 3. Compute layout
        CallGraphAnalyzer.GraphView computed = computer.get();
        if (computed != null && computed.nodes != null) {
            layoutCache.put(cacheKey, computed);
            try {
                jsonMapper.writeValue(cacheFile, computed);
            } catch (Exception e) {
                log.warn("Failed to write graph layout cache to {}: {}", cacheFile.getName(), e.getMessage());
            }
        }
        return computed;
    }

    public void invalidateGraphCache() {
        layoutCache.clear();
        moduleDependencyCache.clear();
        cachedModuleOverview = null;
        scanRevision.incrementAndGet();
        try {
            File dir = getGraphCacheDir();
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            log.info("Cleared in-memory and disk graph layout cache (rev={})", scanRevision.get());
        } catch (Exception e) {
            log.warn("Error invalidating graph cache: {}", e.getMessage());
        }
    }

    public void warmupGraphCache(ScanProgress progress) {
        layoutWarmupRunning.set(true);
        layoutWarmupPhase.set("Precomputing Layouts");
        layoutWarmupPercentage.set(0);
        logProcessBanner("LAYOUT_WARMUP_STARTED", "Sunflower Layout Precomputer", resolveCurrentSourcePath(), "Precomputing graph layouts and module overview");
        try {
            if (progress != null) {
                progress.recordStageStart("LAYOUT", "Graph Layout & Topology Precomputation", "Precomputing sunflower spiral cluster layouts and module dependencies");
            }
            log.info("Starting graph layout precomputation & warm-up...");
            long start = System.currentTimeMillis();

            class LayoutTask {
                final String key;
                final String name;
                final java.util.function.Supplier<CallGraphAnalyzer.GraphView> supplier;
                LayoutTask(String key, String name, java.util.function.Supplier<CallGraphAnalyzer.GraphView> supplier) {
                    this.key = key;
                    this.name = name;
                    this.supplier = supplier;
                }
            }

            int methodCount = callGraph.vertexCount();
            boolean isHugeCodebase = methodCount > 25_000;

            List<LayoutTask> tasks = new ArrayList<>();
            // Always warm up macro architecture views
            tasks.add(new LayoutTask("arch-raw:classes:none", "Architecture Classes", () -> callGraph.architectureGraphView("classes", null)));
            tasks.add(new LayoutTask("arch:classes:none", "Sunflower Clustered (Classes)", () -> callGraph.precomputedArchitectureGraphView("classes", null, (phase, curr, tot, detail) -> {
                if (progress != null) {
                    progress.setCurrentDetail(detail);
                    progress.setDynamicMetrics("Layouts Ready", "1 / " + (isHugeCodebase ? 2 : 6), "Active Layout", "Sunflower (Classes)", "Clusters", String.format("%d / %d", curr, tot), "Placed Nodes", detail.contains("·") ? detail.substring(detail.lastIndexOf('·') + 1).trim() : "Calculating");
                }
            })));

            if (!isHugeCodebase) {
                tasks.add(new LayoutTask("arch-raw:methods:none", "Architecture Methods", () -> callGraph.architectureGraphView("methods", null)));
                tasks.add(new LayoutTask("full-raw:true", "Full Codebase Graph", () -> callGraph.fullGraphView(true)));
                tasks.add(new LayoutTask("full-raw:false", "Method Call Graph", () -> callGraph.fullGraphView(false)));
                tasks.add(new LayoutTask("full:true", "Sunflower Clustered (Full)", () -> callGraph.precomputedFullGraphView(true, (phase, curr, tot, detail) -> {
                    if (progress != null) {
                        progress.setCurrentDetail(detail);
                        progress.setDynamicMetrics("Layouts Ready", "5 / 6", "Active Layout", "Sunflower (Full)", "Clusters", String.format("%d / %d", curr, tot), "Placed Nodes", detail.contains("·") ? detail.substring(detail.lastIndexOf('·') + 1).trim() : "Calculating");
                    }
                })));
            } else {
                log.info("Codebase has {} method vertices (exceeds large-scale threshold of 25,000). Skipping monolithic full method-level graph warmup to conserve memory and avoid thread starvation.",
                    methodCount);
            }

            int total = tasks.size();
            for (int i = 0; i < total; i++) {
                if (cancelRequested) {
                    logProcessBanner("CANCELLED", "Sunflower Layout Precomputer", resolveCurrentSourcePath(), "Layout precomputation cancelled");
                    log.info("Layout precomputation cancelled");
                    return;
                }
                LayoutTask task = tasks.get(i);
                int step = i + 1;
                layoutWarmupPhase.set(task.name);
                layoutWarmupPercentage.set((int) ((i / (float) total) * 100));
                if (progress != null) {
                    progress.setActiveStage("LAYOUT");
                    progress.setCurrentPhase("Precomputing Layouts");
                    progress.setMessage(String.format("Precomputing graph layouts (layout %d of %d)…", step, total));
                    progress.setCurrentDetail(String.format("Layout %d of %d: Generating %s layout", step, total, task.name));
                    progress.setSubProgress(step, total, task.name);
                    progress.setDynamicMetrics(
                        "Layouts Ready", String.format("%d / %d", i, total),
                        "Active Layout", task.name,
                        "Clusters", "Calculating…",
                        "Placed Nodes", "In progress"
                    );
                    progress.setPercentage(92 + (int) ((i / (float) total) * 7.0));
                }
                getOrComputeLayout(task.key, task.supplier);
                if (progress != null) {
                    progress.setDynamicMetrics(
                        "Layouts Ready", String.format("%d / %d", step, total),
                        "Active Layout", task.name,
                        "Clusters", "Complete",
                        "Placed Nodes", "Ready"
                    );
                }
            }

            if (progress != null) {
                progress.setCurrentDetail(String.format("Precomputed all %d graph layouts", total));
                progress.setSubProgress(total, total, "All layouts ready");
            }

            if (cachedModuleOverview == null && !cancelRequested) {
                try {
                    log.info("Precomputing module dependency overview during warm-up...");
                    List<CodePackage> packages = dao.findAllPackages();
                    List<CodeType> types = dao.findAllTypes();
                    List<CodeMethod> methods = isHugeCodebase ? Collections.emptyList() : dao.findAllMethods();
                    List<CodeField> fields = isHugeCodebase ? Collections.emptyList() : dao.findAllFields();
                    List<CodeRelationship> relationships = (callGraph != null && callGraph.getCallGraph() != null)
                        ? dao.findNonCallRelationships()
                        : dao.findAllRelationships();
                    cachedModuleOverview = moduleDependencyAnalyzer.analyzeAll(packages, types, methods, fields, relationships, callGraph);
                    log.info("Precomputed module overview: {} modules ready", cachedModuleOverview != null && cachedModuleOverview.modules != null ? cachedModuleOverview.modules.size() : 0);
                } catch (Exception e) {
                    log.warn("Module overview precomputation deferred: {}", e.getMessage());
                }
            }

            if (progress != null) {
                Map<String, String> layoutMetrics = new LinkedHashMap<>();
                layoutMetrics.put("Layouts Cached", String.valueOf(total));
                layoutMetrics.put("Active Layout", "Sunflower Clustered (Full)");
                layoutMetrics.put("Modules Cached", cachedModuleOverview != null && cachedModuleOverview.modules != null ? String.valueOf(cachedModuleOverview.modules.size()) : "Complete");
                layoutMetrics.put("Placed Nodes", "Ready");
                progress.recordStageEnd("LAYOUT", "COMPLETE", String.format("Precomputed %d topology layouts & module overview", total), layoutMetrics);
            }

            layoutWarmupPhase.set("Ready");
            layoutWarmupPercentage.set(100);

            logProcessBanner("LAYOUT_WARMUP_COMPLETED", "Sunflower Layout Precomputer", resolveCurrentSourcePath(),
                String.format("Finished layout precomputation: %d layouts ready in %d ms", total, System.currentTimeMillis() - start));
            log.info("Finished graph layout & module warm-up: {} layouts ready in {}ms",
                total, System.currentTimeMillis() - start);
        } catch (Exception e) {
            layoutWarmupPhase.set("Error: " + e.getMessage());
            logProcessBanner("LAYOUT_WARMUP_FAILED", "Sunflower Layout Precomputer", resolveCurrentSourcePath(), "Error: " + e.getMessage());
            if (progress != null) {
                progress.recordStageEnd("LAYOUT", "ERROR", "Layout precomputation error: " + e.getMessage(), null);
            }
            log.warn("Graph layout warm-up encountered an error: {}", e.getMessage());
        } finally {
            layoutWarmupRunning.set(false);
        }
    }

    public void warmupGraphCache() {
        warmupGraphCache(null);
    }

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
        this.moduleDependencyAnalyzer = new ModuleDependencyAnalyzer();
        this.reportService         = new ReportService(this.callGraph, this.fieldImpact, this.codeReviewEngine);
        this.criticalPathAnalyzer  = new CriticalPathAnalyzer(this.callGraph);
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
            cfg.http.gzipOnlyCompression();
            // Allow all origins during local use (no cross-origin issues)
            cfg.bundledPlugins.enableCors(cors ->
                cors.addRule(rule -> rule.anyHost()));
        });

        // ── API Telemetry Filters ─────────────────────────────────────────────
        app.before(ctx -> {
            ctx.attribute("startTime", System.currentTimeMillis());
            apiTracker.recordRequestStart(ctx.method().name(), ctx.path());
        });
        app.after(ctx -> {
            Long start = ctx.attribute("startTime");
            long duration = start != null ? (System.currentTimeMillis() - start) : 0;
            apiTracker.recordRequestEnd(ctx.method().name(), ctx.matchedPath(), ctx.status().getCode(), duration);
        });

        // ── Root redirect ─────────────────────────────────────────────────────
        app.get("/", ctx -> ctx.redirect("/index.html"));

        // ── Scan ──────────────────────────────────────────────────────────────
        app.post("/api/scan",              this::startScan);
        app.post("/api/scan/cancel",       this::cancelScan);
        app.get("/api/scan/status",        this::getScanStatus);
        app.get("/api/scan/changes",       this::getScanChanges);
        app.post("/api/scan/incremental",  this::startIncrementalScan);
        app.get("/api/scan/browse",        this::browseFolder);
        app.post("/api/open-folder",       this::openFolder);
        app.post("/api/shutdown",          this::shutdownServer);

        // ── Process & Task Hub ─────────────────────────────────────────────────
        app.get("/api/processes",                  this::listProcesses);
        app.post("/api/processes/{id}/kill",       this::killProcess);
        app.post("/api/processes/{id}/restart",    this::restartProcess);

        // ── Database & Storage ────────────────────────────────────────────────
        app.get("/api/database/health",            this::getDatabaseHealth);
        app.post("/api/database/recover",          this::recoverDatabase);

        // ── Stats ─────────────────────────────────────────────────────────────
        app.get("/api/stats",        this::getStats);


        // ── Packages & Modules ────────────────────────────────────────────────
        app.get("/api/packages",                        this::listPackages);
        app.get("/api/packages/{fqn}/types",            this::typesByPackage);
        app.get("/api/packages/{fqn}/dependencies",     this::getPackageDependencies);
        app.get("/api/modules/dependencies",            this::getAllModuleDependencies);
        app.get("/api/modules/{name}/dependencies",     this::getModuleDependencies);

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
        app.get("/api/graph/precomputed",    this::getPrecomputedGraph);
        app.get("/api/graph/export-json",    this::exportGraphJson);
        app.get("/api/graph/dsm",            this::getDSM);
        app.get("/api/graph/treemap",        this::getTreemap);
        app.get("/api/graph/hub-explorer",   this::getHubExplorer);

        // ── Critical Path & Persistent Entities ──────────────────────────────
        app.get("/api/analysis/persistent-classes", this::getPersistentClasses);
        app.get("/api/analysis/critical-path",       this::getCriticalPath);

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
        app.get("/api/reports/architecture",          this::getArchitectureReport);
        app.get("/api/reports/change-risk",           this::getChangeRiskReport);
        app.get("/api/reports/dead-code",             this::getDeadCodeReport);
        app.get("/api/reports/circular-dependencies", this::getCircularDependenciesReport);
        app.get("/api/reports/archetype-governance",  this::getArchetypeGovernanceReport);
        app.get("/api/reports/review",                this::getReviewReport);
        app.get("/api/reports/metrics",               this::getMetricsReport);
        app.get("/api/reports/html-snapshot",         this::getHtmlSnapshotReport);
        app.get("/api/reports/download",              this::downloadReport);

        // ── Configuration & Deployment Settings (.conf) ──────────────────────
        app.get("/api/config",          this::getConfig);
        app.post("/api/config",         this::saveConfig);
        app.get("/api/config/export",   this::exportConfig);
        app.post("/api/config/import",  this::importConfig);
        app.post("/api/config/reset",   this::resetConfig);

        // ── Scope Management (Exclusion / Restore) ──────────────────────────
        app.post("/api/scope/exclude",  this::excludeScope);
        app.get("/api/scope/excluded",  this::getExcludedScopes);
        app.post("/api/scope/restore",  this::restoreScope);
        app.post("/api/scope/clear",    this::clearExcludedScopes);

        // ── Documentation ──────────────────────────────────────────────────────
        app.get("/api/readme",          this::getReadme);


        // ── Global error handler ──────────────────────────────────────────────
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled error on {} {}: {}", ctx.method(), ctx.path(), e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        });

        // Restore last scan progress state if available
        try {
            ScanProgress lastScan = dao.getLatestScanMeta();
            if (lastScan != null) {
                if (lastScan.getStatus() == ScanProgress.Status.SCANNING) {
                    lastScan.setStatus(ScanProgress.Status.ERROR);
                    lastScan.setActiveStage("ERROR");
                    lastScan.setCurrentPhase("Interrupted");
                    lastScan.setMessage("Previous scan interrupted (server stopped or restarted)");
                    lastScan.setErrorDetail("Process was terminated before scan completed. Click 'Restart' to re-index.");
                    try { dao.saveScanMeta(lastScan); } catch (Exception ignored) {}
                }
                scanState.set(lastScan);
                log.info("Restored last scan progress state for {}", lastScan.getSourcePath());
            }
        } catch (Exception e) {
            log.warn("Failed to load last scan metadata: {}", e.getMessage());
        }

        try {
            int cleaned = dao.cleanupOrphanFileMeta();
            if (cleaned > 0) {
                log.info("Cleaned up {} orphan file_meta records on startup", cleaned);
            }
        } catch (Exception e) {
            log.warn("Failed to clean up orphan file_meta on startup: {}", e.getMessage());
        }

        app.start(port);
        log.info("CodeLens server started on http://localhost:{}", port);

        // Build call graph from database on startup with streaming cursor (independent of scanState)
        try {
            List<String> allMethodFqns = dao.findAllMethodFqns();
            if (!allMethodFqns.isEmpty()) {
                logProcessBanner("GRAPH_BUILD_STARTED", "Call Graph & Topology Engine", resolveCurrentSourcePath(), "Building in-memory call graph from database (" + allMethodFqns.size() + " methods)");
                graphWarmupRunning.set(true);
                graphWarmupPhase.set("Call Graph Analysis");
                graphWarmupPercentage.set(15);

                callGraph.rebuild(allMethodFqns, consumer -> dao.streamCallRelationships(consumer::accept));
                graphWarmupPhase.set("Field Impact Analysis");
                graphWarmupPercentage.set(50);
                int totalFieldRels = dao.countFieldRelationships();
                fieldImpact.rebuildWithStream(consumer -> dao.streamFieldRelationships(consumer::accept), totalFieldRels, callGraph.getCallingMethodFqns());
                graphWarmupPhase.set("Ready");
                graphWarmupPercentage.set(100);
                graphWarmupRunning.set(false);
                logProcessBanner("GRAPH_BUILD_COMPLETED", "Call Graph & Topology Engine", resolveCurrentSourcePath(),
                    String.format("Rebuilt %,d vertices and %,d call edges", callGraph.vertexCount(), callGraph.edgeCount()));
                log.info("Initialized in-memory call graph from database with {} methods",
                    allMethodFqns.size());

                CompletableFuture.runAsync(() -> {
                    try {
                        warmupGraphCache();
                    } catch (Throwable t) {
                        log.warn("Error during startup layout warmup: {}", t.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            graphWarmupRunning.set(false);
            logProcessBanner("GRAPH_BUILD_FAILED", "Call Graph & Topology Engine", resolveCurrentSourcePath(), "Error: " + e.getMessage());
            log.error("Failed to initialize call graph from database on startup: {}", e.getMessage(), e);
        }
    }

    public void stop() {
        cancelRequested = true;
        if (app != null) {
            try { app.stop(); } catch (Exception ignored) {}
        }
        scanExecutor.shutdown();
        try {
            if (!scanExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                scanExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scanExecutor.shutdownNow();
        }
    }

    private void cancelScan(Context ctx) {
        cancelRequested = true;
        ScanProgress current = scanState.get();
        if (current != null && current.getStatus() == ScanProgress.Status.SCANNING) {
            current.setMessage("Cancelling scan...");
        }
        logProcessBanner("CANCEL_REQUESTED", "Active Scan", resolveCurrentSourcePath(), "User requested scan cancellation");
        ctx.json(Map.of("status", "cancelling"));
    }

    private void shutdownServer(Context ctx) {
        log.info("Shutdown requested via API");
        ctx.json(Map.of("status", "shutting_down", "message", "CodeLens server is shutting down gracefully..."));
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
            System.exit(0);
        }, "codelens-shutdown-trigger").start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Console Telemetry & Process Banner Logging
    // ─────────────────────────────────────────────────────────────────────────
    private void logProcessBanner(String eventType, String processName, String path, String details) {
        String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long totalMem = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long usedMem = totalMem - freeMem;

        System.out.println("\n" +
            "╔════════════════════════════════════════════════════════════════════════════════════╗\n" +
            "║ CODELENS BACKGROUND PROCESS " + String.format("%-51s", eventType) + "║\n" +
            "║ Process:   " + String.format("%-69s", processName) + "║\n" +
            "║ Target:    " + String.format("%-69s", path != null ? path : "-") + "║\n" +
            "║ Details:   " + String.format("%-69s", details != null ? details : "-") + "║\n" +
            "║ Timestamp: " + String.format("%-30s", ts) + " Memory: " + String.format("%-30s", usedMem + "MB / " + totalMem + "MB") + "║\n" +
            "╚════════════════════════════════════════════════════════════════════════════════════╝\n");
        log.info("[PROCESS-{}] {} | Target: {} | Details: {}", eventType, processName, path, details);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Process & Task Management Handlers
    // ─────────────────────────────────────────────────────────────────────────
    private void listProcesses(Context ctx) {
        List<Map<String, Object>> processes = new ArrayList<>();
        ScanProgress sp = scanState.get();

        // 1. Source Scanner (Full & Active Scan)
        Map<String, Object> scannerProc = new LinkedHashMap<>();
        scannerProc.put("id", "scanner");
        scannerProc.put("name", "Source Code Scanner");
        scannerProc.put("type", "Parallel AST Parser & Ingestion");
        boolean isScanning = sp != null && sp.getStatus() == ScanProgress.Status.SCANNING;
        boolean isComplete = sp != null && sp.getStatus() == ScanProgress.Status.COMPLETE;
        boolean isError = sp != null && sp.getStatus() == ScanProgress.Status.ERROR;
        scannerProc.put("status", isScanning ? "RUNNING" : (isComplete ? "COMPLETE" : (isError ? "ERROR" : "IDLE")));
        scannerProc.put("activeStage", sp != null ? sp.getActiveStage() : "IDLE");
        scannerProc.put("currentPhase", sp != null ? sp.getCurrentPhase() : "Idle");
        scannerProc.put("currentDetail", sp != null ? (isError ? (sp.getErrorDetail() != null && !sp.getErrorDetail().isBlank() ? sp.getErrorDetail() : sp.getMessage()) : sp.getCurrentDetail()) : "");
        scannerProc.put("percentage", sp != null ? sp.getPercentage() : 0);
        scannerProc.put("sourcePath", sp != null ? sp.getSourcePath() : "");
        scannerProc.put("processedFiles", sp != null ? sp.getProcessedFiles() : 0);
        scannerProc.put("totalFiles", sp != null ? sp.getTotalFiles() : 0);
        scannerProc.put("typesFound", sp != null ? sp.getTypesFound() : 0);
        scannerProc.put("methodsFound", sp != null ? sp.getMethodsFound() : 0);
        scannerProc.put("fieldsFound", sp != null ? sp.getFieldsFound() : 0);
        scannerProc.put("durationMs", sp != null ? sp.getDurationMs() : 0);
        scannerProc.put("startTime", sp != null ? sp.getStartTime() : 0);
        scannerProc.put("thread", isScanning ? "codelens-scan-worker" : "-");
        scannerProc.put("canKill", isScanning);
        scannerProc.put("canRestart", true);
        processes.add(scannerProc);

        // 2. Incremental Delta Scanner
        Map<String, Object> deltaProc = new LinkedHashMap<>();
        deltaProc.put("id", "delta-scanner");
        deltaProc.put("name", "Delta Change Scanner");
        deltaProc.put("type", "File Watcher & Incremental Patch");
        boolean isDeltaActive = isScanning && sp != null && ("Delta Change Detection".equals(sp.getCurrentPhase()) || "Incremental AST Parsing".equals(sp.getCurrentPhase()));
        deltaProc.put("status", isDeltaActive ? "RUNNING" : "IDLE");
        deltaProc.put("activeStage", isDeltaActive ? sp.getActiveStage() : "IDLE");
        deltaProc.put("currentPhase", isDeltaActive ? sp.getCurrentPhase() : "Idle");
        deltaProc.put("percentage", isDeltaActive ? sp.getPercentage() : 0);
        deltaProc.put("thread", isDeltaActive ? "codelens-scan-worker" : "-");
        deltaProc.put("canKill", isDeltaActive);
        deltaProc.put("canRestart", true);
        processes.add(deltaProc);

        // 3. Call Graph & Topology Engine
        Map<String, Object> graphProc = new LinkedHashMap<>();
        graphProc.put("id", "call-graph");
        graphProc.put("name", "Call Graph & Topology Engine");
        graphProc.put("type", "JGraphT Topology & Field Impact");
        boolean isScanGraphActive = isScanning && sp != null && ("Call Graph Analysis".equals(sp.getCurrentPhase()) || "Field Impact Analysis".equals(sp.getCurrentPhase()) || "GRAPH".equals(sp.getActiveStage()));
        boolean isWarmupGraphActive = graphWarmupRunning.get();
        boolean isGraphBuilding = isScanGraphActive || isWarmupGraphActive;
        int vCount = callGraph != null ? callGraph.vertexCount() : 0;
        int eCount = callGraph != null ? callGraph.edgeCount() : 0;
        graphProc.put("status", isGraphBuilding ? "RUNNING" : (vCount > 0 ? "COMPLETE" : "IDLE"));
        graphProc.put("activeStage", isGraphBuilding ? "GRAPH" : "IDLE");
        String graphPhase = isScanGraphActive ? sp.getCurrentPhase() : (isWarmupGraphActive ? graphWarmupPhase.get() : (vCount > 0 ? "In-Memory Graph Ready" : "Idle"));
        int graphPct = isScanGraphActive ? sp.getPercentage() : (isWarmupGraphActive ? graphWarmupPercentage.get() : (vCount > 0 ? 100 : 0));
        graphProc.put("currentPhase", graphPhase);
        graphProc.put("currentDetail", String.format("%,d vertices · %,d call edges", vCount, eCount));
        graphProc.put("percentage", graphPct);
        graphProc.put("thread", isGraphBuilding ? "codelens-graph-builder" : "-");
        graphProc.put("canKill", isScanGraphActive);
        graphProc.put("canRestart", true);
        processes.add(graphProc);

        // 4. Layout Precomputation Engine
        Map<String, Object> layoutProc = new LinkedHashMap<>();
        layoutProc.put("id", "layout-engine");
        layoutProc.put("name", "Sunflower Layout Precomputer");
        layoutProc.put("type", "Sunflower Spiral & Clustering Precomputer");
        boolean isScanLayoutActive = isScanning && sp != null && ("Precomputing Layouts".equals(sp.getCurrentPhase()) || "LAYOUT".equals(sp.getActiveStage()));
        boolean isWarmupLayoutActive = layoutWarmupRunning.get();
        boolean isLayoutBuilding = isScanLayoutActive || isWarmupLayoutActive;
        int cachedLayouts = layoutCache.size();
        layoutProc.put("status", isLayoutBuilding ? "RUNNING" : (cachedLayouts > 0 ? "COMPLETE" : "IDLE"));
        layoutProc.put("activeStage", isLayoutBuilding ? "LAYOUT" : "IDLE");
        String layoutPhase = isScanLayoutActive ? sp.getCurrentPhase() : (isWarmupLayoutActive ? layoutWarmupPhase.get() : (cachedLayouts > 0 ? "Cached Layouts Ready" : "Idle"));
        int layoutPct = isScanLayoutActive ? sp.getPercentage() : (isWarmupLayoutActive ? layoutWarmupPercentage.get() : (cachedLayouts > 0 ? 100 : 0));
        layoutProc.put("currentPhase", layoutPhase);
        layoutProc.put("currentDetail", String.format("%d layouts cached in memory (rev=%d)", cachedLayouts, scanRevision.get()));
        layoutProc.put("percentage", layoutPct);
        layoutProc.put("thread", isLayoutBuilding ? "codelens-layout-worker" : "-");
        layoutProc.put("canKill", isScanLayoutActive);
        layoutProc.put("canRestart", true);
        processes.add(layoutProc);

        // 5. Git History & Hotspots
        Map<String, Object> gitProc = new LinkedHashMap<>();
        gitProc.put("id", "git-analyzer");
        gitProc.put("name", "Git Churn & Hotspot Analyzer");
        gitProc.put("type", "Git Log & Code Churn Correlator");
        GitAnalysisProgress gp = gitProgress.get();
        boolean isGitRunning = gp != null && gp.getStatus() == GitAnalysisProgress.Status.RUNNING;
        boolean isGitComplete = gp != null && gp.getStatus() == GitAnalysisProgress.Status.COMPLETE;
        boolean isGitError = gp != null && gp.getStatus() == GitAnalysisProgress.Status.ERROR;
        gitProc.put("status", isGitRunning ? "RUNNING" : (isGitComplete ? "COMPLETE" : (isGitError ? "ERROR" : "IDLE")));
        gitProc.put("activeStage", isGitRunning ? "GIT_ANALYSIS" : "IDLE");
        String gitPhase = isGitRunning 
            ? (gp.getCurrentFile() != null && !gp.getCurrentFile().isBlank() ? "Blame: " + gp.getCurrentFile() : "Auditing Blame & Churn") 
            : (isGitComplete ? "Git Churn Analysis Ready" : (isGitError ? "Analysis Failed" : "Ready"));
        gitProc.put("currentPhase", gitPhase);
        String gitDetail = gp != null && gp.getMessage() != null && !gp.getMessage().isBlank()
            ? gp.getMessage()
            : (isGitComplete 
                ? String.format("%,d entities annotated across %,d files", gp.getEntitiesAnnotated(), gp.getTotalFiles()) 
                : "Git commit history and churn correlator");
        gitProc.put("currentDetail", gitDetail);
        gitProc.put("percentage", gp != null ? gp.getPercentage() : 0);
        gitProc.put("durationMs", gp != null && gp.getStartTime() > 0 
            ? (gp.getEndTime() > 0 ? gp.getEndTime() - gp.getStartTime() : System.currentTimeMillis() - gp.getStartTime()) 
            : 0);
        gitProc.put("startTime", gp != null ? gp.getStartTime() : 0);
        gitProc.put("thread", isGitRunning ? "codelens-git-worker" : "-");
        gitProc.put("canKill", isGitRunning);
        gitProc.put("canRestart", true);
        processes.add(gitProc);

        // 6. Database Connection Watchdog
        Map<String, Object> watchdogProc = new LinkedHashMap<>();
        watchdogProc.put("id", "db-watchdog");
        watchdogProc.put("name", "Database Connection Watchdog");
        watchdogProc.put("type", "HikariCP Leak Detector & Auto-Recovery");
        watchdogProc.put("status", "IDLE");
        watchdogProc.put("activeStage", "IDLE");
        watchdogProc.put("currentPhase", "Watchdog Active (10s interval)");
        watchdogProc.put("currentDetail", String.format("%d active connection leases · %d leaks recovered", db.getActiveLeaseCount(), db.getRecoveredLeakCount()));
        watchdogProc.put("percentage", 100);
        watchdogProc.put("thread", "CodeLens-DbLeakWatchdog");
        watchdogProc.put("canKill", false);
        watchdogProc.put("canRestart", true);
        processes.add(watchdogProc);

        // System resources & Pool telemetry
        long freeMem = Runtime.getRuntime().freeMemory();
        long totalMem = Runtime.getRuntime().totalMemory();
        long maxMem = Runtime.getRuntime().maxMemory();
        long usedMem = totalMem - freeMem;

        Map<String, Object> system = new LinkedHashMap<>();
        system.put("heapUsedMb", usedMem / (1024 * 1024));
        system.put("heapTotalMb", totalMem / (1024 * 1024));
        system.put("heapMaxMb", maxMem / (1024 * 1024));
        system.put("heapPercent", (int) ((usedMem * 100L) / maxMem));
        system.put("activeThreads", Thread.activeCount());
        system.put("dbPool", db.getPoolStats());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("processes", processes);
        resp.put("system", system);
        resp.put("database", db.getDiagnostics());
        resp.put("apis", Map.of(
            "summary", apiTracker.getSummary(),
            "endpoints", apiTracker.getEndpoints()
        ));
        ctx.json(resp);
    }

    private void getDatabaseHealth(Context ctx) {
        ctx.json(db.runHealthCheck());
    }

    private void recoverDatabase(Context ctx) {
        Map<?, ?> body = Collections.emptyMap();
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception ignored) {}
        String action = body != null && body.containsKey("action") ? String.valueOf(body.get("action")) : "health_check";

        String message;
        boolean success = true;
        try {
            if ("restart".equalsIgnoreCase(action)) {
                Map<String, Object> restartReport = db.restartDatabase();
                success = Boolean.TRUE.equals(restartReport.get("success"));
                message = String.valueOf(restartReport.get("message"));
            } else if ("sweep_leaks".equalsIgnoreCase(action) || "reclaim_leaks".equalsIgnoreCase(action)) {
                Map<String, Object> sweepReport = db.sweepConnectionLeaks();
                success = Boolean.TRUE.equals(sweepReport.get("success"));
                message = String.valueOf(sweepReport.get("message"));
            } else if ("reindex".equalsIgnoreCase(action)) {
                db.finishBulkLoad();
                message = "Secondary indexes successfully rebuilt and optimizer statistics analyzed.";
            } else if ("compact".equalsIgnoreCase(action)) {
                db.compactDatabase();
                message = "Database compacted via SHUTDOWN COMPACT; connection pool reconnected.";
            } else if ("clean_orphans".equalsIgnoreCase(action)) {
                int purged = db.purgeOrphanData();
                message = "Purged " + purged + " dangling/orphan records from database.";
            } else {
                message = "Database health check completed.";
            }
        } catch (Exception e) {
            success = false;
            message = "Recovery action '" + action + "' failed: " + e.getMessage();
            log.error("Database recovery action failed: {}", e.getMessage(), e);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", success);
        response.put("action", action);
        response.put("message", message);
        response.put("diagnostics", db.getDiagnostics());
        logProcessBanner("DB_" + action.toUpperCase() + (success ? "_COMPLETED" : "_FAILED"),
            "Database Manager", "./codelens-data/codelens_db", message);
        ctx.json(response);
    }

    private void killProcess(Context ctx) {
        String id = ctx.pathParam("id");
        logProcessBanner("KILL_REQUESTED", id.toUpperCase(), resolveCurrentSourcePath(), "User requested process termination");
        if ("scanner".equalsIgnoreCase(id) || "delta-scanner".equalsIgnoreCase(id) || "all".equalsIgnoreCase(id)) {
            cancelRequested = true;
            ScanProgress sp = scanState.get();
            if (sp != null && sp.getStatus() == ScanProgress.Status.SCANNING) {
                sp.setStatus(ScanProgress.Status.ERROR);
                sp.setMessage("Scan terminated by user via Process Hub");
                sp.setErrorDetail("Process manually killed");
                sp.setEndTime(System.currentTimeMillis());
                try { dao.saveScanMeta(sp); } catch (Exception ignored) {}
                try { db.finishBulkLoad(); } catch (Exception ignored) {}
            }
            ctx.json(Map.of("status", "killed", "processId", id, "message", "Scan process successfully terminated"));
            return;
        } else if ("git-analyzer".equalsIgnoreCase(id)) {
            GitAnalysisProgress gp = gitProgress.get();
            if (gp != null && gp.getStatus() == GitAnalysisProgress.Status.RUNNING) {
                gp.setStatus(GitAnalysisProgress.Status.ERROR);
                gp.setMessage("Git analysis cancelled by user via Process Hub");
                gp.setErrorDetail("Process manually killed");
                gp.setEndTime(System.currentTimeMillis());
                logProcessBanner("CANCELLED", "Git Churn & Hotspot Analyzer", gp.getRepoPath(), "Git analysis cancelled by user");
            }
            ctx.json(Map.of("status", "killed", "processId", id, "message", "Git analysis process terminated"));
            return;
        }
        ctx.json(Map.of("status", "ok", "processId", id, "message", "Process signaled"));
    }

    private void restartProcess(Context ctx) {
        String id = ctx.pathParam("id");
        String currentPath = resolveCurrentSourcePath();
        if (currentPath == null || currentPath.isBlank()) {
            ctx.status(400).json(Map.of("error", "No active source path to restart"));
            return;
        }
        logProcessBanner("RESTART_REQUESTED", id.toUpperCase(), currentPath, "User requested process restart via Process Hub");
        if ("scanner".equalsIgnoreCase(id)) {
            ScanProgress current = scanState.get();
            if (current != null && current.getStatus() == ScanProgress.Status.SCANNING) {
                cancelRequested = true;
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
            ScanProgress progress = new ScanProgress(ScanProgress.Status.SCANNING);
            progress.setSourcePath(currentPath);
            progress.setStartTime(System.currentTimeMillis());
            progress.setMessage("Restarting full scan…");
            scanState.set(progress);
            scanExecutor.submit(() -> runScan(currentPath, resolveCurrentExcludePatterns(), progress));
            ctx.json(Map.of("status", "restarted", "processId", id, "sourcePath", currentPath));
            return;
        } else if ("delta-scanner".equalsIgnoreCase(id)) {
            ScanProgress progress = new ScanProgress(ScanProgress.Status.SCANNING);
            progress.setSourcePath(currentPath);
            progress.setStartTime(System.currentTimeMillis());
            progress.setCurrentPhase("Delta Change Detection");
            progress.setMessage("Resuming & rescanning changed files…");
            scanState.set(progress);
            scanExecutor.submit(() -> runIncrementalScan(currentPath, resolveCurrentExcludePatterns(), progress));
            ctx.json(Map.of("status", "restarted", "processId", id, "sourcePath", currentPath));
            return;
        } else if ("call-graph".equalsIgnoreCase(id)) {
            graphWarmupRunning.set(true);
            graphWarmupPhase.set("Call Graph Analysis");
            graphWarmupPercentage.set(10);
            logProcessBanner("GRAPH_BUILD_STARTED", "Call Graph & Topology Engine", currentPath, "Manual rebuild of call graph & field impact requested");
            scanExecutor.submit(() -> {
                try {
                    log.info("Manual rebuild of call graph & field impact requested");
                    List<String> allMethodFqns = dao.findAllMethodFqns();
                    callGraph.rebuild(allMethodFqns, consumer -> {
                        try { dao.streamCallRelationships(consumer::accept); } catch (Exception e) { throw new RuntimeException(e); }
                    }, null);
                    graphWarmupPhase.set("Field Impact Analysis");
                    graphWarmupPercentage.set(50);
                    int totalFieldRels = dao.countFieldRelationships();
                    fieldImpact.rebuildWithStream(consumer -> {
                        try { dao.streamFieldRelationships(consumer::accept); } catch (Exception e) { throw new RuntimeException(e); }
                    }, totalFieldRels, callGraph.getCallingMethodFqns(), null);
                    graphWarmupPhase.set("Ready");
                    graphWarmupPercentage.set(100);
                    logProcessBanner("GRAPH_BUILD_COMPLETED", "Call Graph & Topology Engine", currentPath,
                        String.format("Manual rebuild complete: %,d vertices, %,d call edges", callGraph.vertexCount(), callGraph.edgeCount()));
                    log.info("Manual rebuild of call graph complete: {} vertices", callGraph.vertexCount());
                } catch (Exception e) {
                    log.error("Failed to rebuild call graph", e);
                    graphWarmupPhase.set("Error: " + e.getMessage());
                    logProcessBanner("GRAPH_BUILD_FAILED", "Call Graph & Topology Engine", currentPath, "Error: " + e.getMessage());
                } finally {
                    graphWarmupRunning.set(false);
                }
            });
            ctx.json(Map.of("status", "restarted", "processId", id, "message", "Call graph rebuild queued"));
            return;
        } else if ("layout-engine".equalsIgnoreCase(id)) {
            invalidateGraphCache();
            scanExecutor.submit(() -> warmupGraphCache());
            ctx.json(Map.of("status", "restarted", "processId", id, "message", "Layout precomputations queued"));
            return;
        } else if ("git-analyzer".equalsIgnoreCase(id)) {
            String repoPath = currentPath;
            GitAnalysisProgress gp = gitProgress.get();
            if (gp != null && gp.getRepoPath() != null && !gp.getRepoPath().isBlank()) {
                repoPath = gp.getRepoPath();
            }
            GitRepoLocator.ValidationResult validation = GitRepoLocator.validate(repoPath);
            if (!validation.isValid()) {
                ctx.status(400).json(Map.of("error", "Source path is not a valid Git repository: " + validation.getError()));
                return;
            }
            triggerGitAnalysis(validation.getRepoPath(), validation.getBranch());
            ctx.json(Map.of("status", "restarted", "processId", id, "repoPath", validation.getRepoPath()));
            return;
        } else if ("db-watchdog".equalsIgnoreCase(id)) {
            Map<String, Object> sweepReport = db.sweepConnectionLeaks();
            String sweepMsg = String.valueOf(sweepReport.get("message"));
            logProcessBanner("DB_LEAK_SWEEP", "Database Connection Watchdog", "codelens_db", sweepMsg);
            ctx.json(Map.of("status", "restarted", "processId", id, "message", sweepMsg, "report", sweepReport));
            return;
        }
        ctx.status(400).json(Map.of("error", "Unknown process id: " + id));
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

        boolean resume = false;
        if (body.get("resume") != null) {
            resume = Boolean.parseBoolean(body.get("resume").toString());
        }

        // Initialise progress object
        ScanProgress progress = new ScanProgress(ScanProgress.Status.SCANNING);
        progress.setSourcePath(sourcePath);
        progress.setStartTime(System.currentTimeMillis());
        progress.setMessage(resume ? "Resuming scan…" : "Initialising scanner…");
        scanState.set(progress);

        // Launch background scan task
        final List<String> finalExcludes = excludePatterns;
        this.lastExcludePatterns = excludePatterns != null ? excludePatterns : Collections.emptyList();
        final String finalPath = sourcePath;
        final boolean isResume = resume;
        logProcessBanner(isResume ? "RESUME_TRIGGERED" : "SCAN_TRIGGERED",
            isResume ? "Resume / Incremental Scan" : "Full Codebase Scan",
            sourcePath,
            "Excludes: " + (finalExcludes != null && !finalExcludes.isEmpty() ? String.join(", ", finalExcludes) : "default"));
        if (isResume) {
            progress.setCurrentPhase("Delta Change Detection");
            progress.setMessage("Resuming scan — detecting modified & remaining source files…");
            scanExecutor.submit(() -> runIncrementalScan(finalPath, finalExcludes, progress));
        } else {
            scanExecutor.submit(() -> runScan(finalPath, finalExcludes, progress));
        }

        ctx.status(202).json(Map.of("status", "accepted", "sourcePath", sourcePath, "resumed", isResume));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Handler: GET /api/scan/status
    // ─────────────────────────────────────────────────────────────────────────
    private void getScanStatus(Context ctx) {
        ctx.json(scanState.get());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Handler: GET /api/scan/changes
    // ─────────────────────────────────────────────────────────────────────────
    private void getScanChanges(Context ctx) {
        String sourcePath = ctx.queryParam("sourcePath");
        if (sourcePath == null || sourcePath.isBlank()) {
            ScanProgress sp = scanState.get();
            if (sp != null && sp.getSourcePath() != null) {
                sourcePath = sp.getSourcePath();
            }
        }
        if (sourcePath == null || sourcePath.isBlank()) {
            ctx.json(new ScanChanges());
            return;
        }

        try {
            Path root = Paths.get(sourcePath);
            if (!Files.exists(root)) {
                ctx.status(404).json(Map.of("error", "Source path does not exist: " + sourcePath));
                return;
            }
            Map<String, FileMeta> existingMeta = dao.getAllFileMeta();
            JavaSourceScanner scanner = new JavaSourceScanner();
            ScanChanges changes = scanner.detectDiskChanges(root, existingMeta, null);
            ctx.json(changes);
        } catch (Exception e) {
            log.error("Failed to detect scan changes: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to detect changes: " + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Handler: POST /api/scan/incremental
    // ─────────────────────────────────────────────────────────────────────────
    private void startIncrementalScan(Context ctx) {
        Map<?, ?> body = ctx.bodyAsClass(Map.class);
        String sourcePath = (String) body.get("sourcePath");
        if (sourcePath == null || sourcePath.isBlank()) {
            ScanProgress current = scanState.get();
            if (current != null && current.getSourcePath() != null) {
                sourcePath = current.getSourcePath();
            }
        }
        if (sourcePath == null || sourcePath.isBlank()) {
            sourcePath = resolveCurrentSourcePath();
        }
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
        if (excludePatterns == null || excludePatterns.isEmpty()) {
            excludePatterns = resolveCurrentExcludePatterns();
        }

        ScanProgress current = scanState.get();
        if (current.getStatus() == ScanProgress.Status.SCANNING) {
            ctx.status(409).json(Map.of("error", "Scan already in progress"));
            return;
        }

        try {
            dao.cleanupOrphanFileMeta();
        } catch (Exception e) {
            log.warn("Failed to cleanup orphan file_meta before incremental scan: {}", e.getMessage());
        }

        ScanProgress progress = new ScanProgress(ScanProgress.Status.SCANNING);
        progress.setSourcePath(sourcePath);
        progress.setCurrentPhase("Delta Change Detection");
        progress.setMessage("Detecting modified and new source files…");
        progress.setStartTime(System.currentTimeMillis());
        scanState.set(progress);

        final List<String> finalExcludes = excludePatterns;
        this.lastExcludePatterns = excludePatterns != null ? excludePatterns : Collections.emptyList();
        final String finalPath = sourcePath;
        logProcessBanner("INCREMENTAL_TRIGGERED", "Delta Change Detection & Rescan", sourcePath,
            "Excludes: " + (finalExcludes != null && !finalExcludes.isEmpty() ? String.join(", ", finalExcludes) : "default"));
        scanExecutor.submit(() -> runIncrementalScan(finalPath, finalExcludes, progress));

        ctx.status(202).json(Map.of("status", "accepted", "sourcePath", sourcePath));
    }

    private String resolveCurrentSourcePath() {
        ScanProgress sp = scanState.get();
        if (sp != null && sp.getSourcePath() != null && !sp.getSourcePath().isBlank()) {
            return sp.getSourcePath();
        }
        try {
            ScanProgress dbSp = dao.getLatestScanMeta();
            if (dbSp != null && dbSp.getSourcePath() != null && !dbSp.getSourcePath().isBlank()) {
                return dbSp.getSourcePath();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private List<String> resolveCurrentExcludePatterns() {
        List<String> list = lastExcludePatterns;
        return list != null ? list : Collections.emptyList();
    }

    private List<FileMeta> filterFileMetas(List<FileMeta> metas, Set<String> excludedPkgFqns, Set<String> excludedSourceFiles) {
        if (metas == null || metas.isEmpty()) return Collections.emptyList();
        if ((excludedPkgFqns == null || excludedPkgFqns.isEmpty()) && (excludedSourceFiles == null || excludedSourceFiles.isEmpty())) {
            return metas;
        }
        List<FileMeta> kept = new ArrayList<>(metas.size());
        for (FileMeta fm : metas) {
            if (fm == null || fm.getFilePath() == null) continue;
            if (excludedSourceFiles != null && excludedSourceFiles.contains(fm.getFilePath())) {
                continue;
            }
            String norm = fm.getFilePath().replace('\\', '/');
            boolean isExcluded = false;
            if (excludedPkgFqns != null) {
                for (String pkg : excludedPkgFqns) {
                    String pkgSlash = "/" + pkg.replace('.', '/') + "/";
                    if (norm.contains(pkgSlash) || norm.endsWith("/" + pkg.replace('.', '/') + ".java")) {
                        isExcluded = true;
                        break;
                    }
                }
            }
            if (!isExcluded) {
                kept.add(fm);
            }
        }
        return kept;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Background scan task
    // ─────────────────────────────────────────────────────────────────────────
    private void runScan(String sourcePath, List<String> excludePatterns, ScanProgress progress) {
        cancelRequested = false;
        try {
            // Phase 1: prepare database and lucene index
            progress.setActiveStage("PREPARE");
            progress.recordStageStart("PREPARE", "Preparing Storage", "Clearing database tables and enabling bulk ingestion mode");
            progress.setCurrentPhase("Preparing Storage");
            progress.setMessage("Clearing existing database & index data…");
            progress.setCurrentDetail("Resetting schema & indices");
            progress.setPercentage(1);
            db.clearAll();
            db.prepareForBulkLoad();
            lucene.prepareIndexRebuild();
            progress.recordStageEnd("PREPARE", "COMPLETE", "Database and search index reset successfully", Map.of("Status", "Ready", "Mode", "Bulk Load"));

            // Phase 2: bounded streaming scan
            progress.setActiveStage("PARSE");
            progress.recordStageStart("PARSE", "AST Parsing & Storage", "Scanning Java source files and extracting AST nodes in parallel");
            progress.setCurrentPhase("AST Parsing & Storage");
            progress.setMessage("Scanning Java source files in parallel…");

            List<ExcludedScope> excludedScopes = Collections.emptyList();
            try {
                excludedScopes = dao.findAllExcludedScopes();
            } catch (Exception ignored) {}
            final Set<String> excludedTypeFqns = new HashSet<>();
            final Set<String> excludedPkgFqns = new HashSet<>();
            final Set<String> excludedSourceFiles = new HashSet<>();
            for (ExcludedScope s : excludedScopes) {
                if ("PACKAGE".equalsIgnoreCase(s.entityType())) {
                    excludedPkgFqns.add(s.fqn());
                } else {
                    excludedTypeFqns.add(s.fqn());
                    if (s.sourceFile() != null && !s.sourceFile().isBlank()) {
                        excludedSourceFiles.add(s.sourceFile());
                    }
                }
            }

            JavaSourceScanner scanner = new JavaSourceScanner();
            JavaSourceScanner.ScanResult result = scanner.scan(
                sourcePath,
                excludePatterns,
                new JavaSourceScanner.BatchConsumer() {
                    @Override
                    public void onBatch(List<CodePackage> pkgs, List<CodeType> types, List<CodeField> fields,
                                        List<CodeMethod> methods, List<CodeRelationship> rels) throws Exception {
                        onBatch(pkgs, types, fields, methods, rels, Collections.emptyList());
                    }

                    @Override
                    public void onBatch(List<CodePackage> pkgs, List<CodeType> types, List<CodeField> fields,
                                        List<CodeMethod> methods, List<CodeRelationship> rels,
                                        List<FileMeta> fileMetas) throws Exception {
                        FilteredBatch fb = filterExcludedScopeBatch(pkgs, types, fields, methods, rels, fileMetas, excludedTypeFqns, excludedPkgFqns, excludedSourceFiles);
                        dao.batchInsertChunkFast(fb.pkgs, fb.types, fb.fields, fb.methods, fb.rels, fb.fileMetas);
                        lucene.addBatch(fb.types, fb.methods, fb.fields);
                    }
                },
                new JavaSourceScanner.ProgressCallback() {
                    @Override
                    public void onFile(int done, int total, String file) {
                        onProgress(done, total, file, progress.getTypesFound(), progress.getMethodsFound(), progress.getFieldsFound(), progress.getRelationshipsFound());
                    }

                    @Override
                    public void onProgress(int done, int total, String file, int types, int methods, int fields, int rels) {
                        progress.setTypesFound(types);
                        progress.setMethodsFound(methods);
                        progress.setFieldsFound(fields);
                        progress.setRelationshipsFound(rels);
                        progress.setTotalFiles(total);
                        progress.setProcessedFiles(done);
                        String fileName = file;
                        if (file != null && !file.startsWith("Persisting")) {
                            int lastSlash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
                            fileName = lastSlash >= 0 ? file.substring(lastSlash + 1) : file;
                            progress.setCurrentDetail(fileName);
                            progress.setMessage(String.format("Parsing %s (%d/%d)", fileName, done, total));
                        } else if (file != null) {
                            progress.setCurrentDetail("Persisting parsed records to database…");
                            progress.setMessage("Flushing & persisting parsed records…");
                        }
                        float parseFraction = total > 0 ? (float) done / total : 0f;
                        progress.setPercentage(2 + (int) (parseFraction * 68)); // 2% -> 70%
                        progress.setSubProgress(done, total, fileName);
                        progress.setDynamicMetrics(
                            "Types", String.format("%,d", types),
                            "Methods", String.format("%,d", methods),
                            "Fields", String.format("%,d", fields),
                            "Relationships", String.format("%,d", rels)
                        );
                    }
                },
                () -> cancelRequested);

            if (result.cancelled || cancelRequested) {
                log.info("Scan cancelled for {}", sourcePath);
                logProcessBanner("CANCELLED", "Full Codebase Scan", sourcePath, "Scan was cancelled by user");
                try { db.finishBulkLoad(); } catch (Exception ignored) {}
                progress.recordStageEnd(progress.getActiveStage(), "CANCELLED", "Scan cancelled by user", null);
                progress.setActiveStage("COMPLETE");
                progress.setCurrentPhase("Cancelled");
                progress.setCurrentDetail("Scan cancelled by user");
                progress.setMessage(String.format("Scan cancelled (%d/%d files processed)", progress.getProcessedFiles(), progress.getTotalFiles()));
                progress.setStatus(ScanProgress.Status.ERROR);
                progress.setErrorDetail("Scan cancelled by user");
                progress.setEndTime(System.currentTimeMillis());
                dao.saveScanMeta(progress);
                return;
            }

            // Save file metadata for delta change detection if any non-chunk items remain
            if (!result.fileMetas.isEmpty()) {
                List<FileMeta> filteredFinal = filterFileMetas(result.fileMetas, excludedPkgFqns, excludedSourceFiles);
                if (!filteredFinal.isEmpty()) {
                    dao.saveFileMetaBatch(filteredFinal);
                }
            }

            Map<String, String> parseMetrics = new LinkedHashMap<>();
            parseMetrics.put("Files Parsed", String.format("%,d", result.parsedFiles));
            parseMetrics.put("Types Found", String.format("%,d", result.typesFound));
            parseMetrics.put("Methods Found", String.format("%,d", result.methodsFound));
            parseMetrics.put("Fields Found", String.format("%,d", result.fieldsFound));
            parseMetrics.put("Relationships", String.format("%,d", result.relationshipsFound));
            progress.recordStageEnd("PARSE", "COMPLETE", String.format("Parsed %,d files; discovered %,d types & %,d methods", result.parsedFiles, result.typesFound, result.methodsFound), parseMetrics);

            // Phase 3: finish Lucene commit & rebuild secondary database indexes
            progress.setActiveStage("INDEX");
            progress.recordStageStart("INDEX", "Search Index & Database Index Rebuild", "Committing Lucene search documents & rebuilding secondary database B-tree indexes");
            progress.setCurrentPhase("Finalizing Index");
            progress.setMessage("Committing search index & rebuilding database indexes…");
            progress.setCurrentDetail("Committing Lucene search documents…");
            progress.setPercentage(71);
            int totalDocsEstimate = result.typesFound + result.methodsFound + result.fieldsFound;
            progress.setSubProgress(1, 13, "Lucene Search Index");
            progress.setDynamicMetrics(
                "Lucene Docs", String.format("%,d", totalDocsEstimate),
                "DB Indexes", "0 / 12",
                "Target Table", "lucene-index",
                "Indexed Records", String.format("%,d docs", totalDocsEstimate)
            );
            lucene.finishIndexRebuild();

            progress.setCurrentDetail("Rebuilding secondary database indexes…");
            progress.setPercentage(72);
            db.finishBulkLoad((step, totalSteps, indexName, tableName, description) -> {
                progress.setSubProgress(step, totalSteps, indexName);
                float fraction = (float) step / totalSteps;
                progress.setPercentage(72 + (int)(fraction * 2.0)); // 72% -> 74%
                progress.setMessage(String.format("Rebuilding DB indexes (index %d of %d)…", step, totalSteps));
                progress.setCurrentDetail(String.format("Index %d of %d: %s (%s)", step, totalSteps, indexName, description));

                String rowEstimate = "-";
                if ("types".equalsIgnoreCase(tableName)) rowEstimate = String.format("%,d rows", result.typesFound);
                else if ("methods".equalsIgnoreCase(tableName)) rowEstimate = String.format("%,d rows", result.methodsFound);
                else if ("fields".equalsIgnoreCase(tableName)) rowEstimate = String.format("%,d rows", result.fieldsFound);
                else if ("relationships".equalsIgnoreCase(tableName)) rowEstimate = String.format("%,d rows", result.relationshipsFound);
                else if ("packages".equalsIgnoreCase(tableName)) rowEstimate = "package hierarchy";
                else if ("all tables".equalsIgnoreCase(tableName)) rowEstimate = "full database";
                else rowEstimate = tableName;

                progress.setDynamicMetrics(
                    "Lucene Docs", String.format("%,d", totalDocsEstimate),
                    "DB Indexes", String.format("%d / %d", step, totalSteps),
                    "Target Table", tableName,
                    "Indexed Records", rowEstimate
                );
            });

            Map<String, String> indexMetrics = new LinkedHashMap<>();
            indexMetrics.put("Lucene Docs", String.format("%,d", totalDocsEstimate));
            indexMetrics.put("DB Indexes", "12 / 12 rebuilt");
            indexMetrics.put("Search Index", "Committed");
            indexMetrics.put("Storage Engine", "H2 MVStore");
            progress.recordStageEnd("INDEX", "COMPLETE", String.format("Committed %,d Lucene docs & rebuilt 12 secondary DB indexes", totalDocsEstimate), indexMetrics);

            if (cancelRequested) {
                return;
            }

            // Phase 4: rebuild in-memory call graph and field impact with streaming cursor
            progress.setActiveStage("GRAPH");
            progress.recordStageStart("GRAPH", "Call Graph Analysis & Field Propagation", "Computing method call hierarchy, caller triggers, and field impact propagation");
            progress.setCurrentPhase("Call Graph Analysis");
            progress.setMessage("Computing call graph & topology…");
            progress.setPercentage(75);
            progress.setSubProgress(1, 4, "Querying methods from storage");
            progress.setDynamicMetrics("Graph Vertices", "Querying…", "Call Edges", "Pending", "Field Links", "Pending", "Caller Triggers", "Pending");

            List<String> allMethodFqns = dao.findAllMethodFqns();
            int totalMethods = allMethodFqns.size();
            progress.setCurrentDetail(String.format("Fetched %,d methods from storage", totalMethods));
            progress.setSubProgress(1, 4, "Querying call pairs from storage");
            progress.setDynamicMetrics("Graph Vertices", String.format("%,d loaded", totalMethods), "Call Edges", "Querying…", "Field Links", "Pending", "Caller Triggers", "Pending");

            int totalCallEdges = dao.countCallRelationships();
            progress.setCurrentDetail(String.format("Found %,d call relationships; building graph vertices…", totalCallEdges));
            progress.setSubProgress(2, 4, "Mapping call graph");

            callGraph.rebuild(allMethodFqns, consumer -> dao.streamCallRelationships(consumer::accept), (phase, curr, total, detail) -> {
                if ("Call Graph: Indexing Methods".equals(phase)) {
                    float f = total > 0 ? (float) curr / total : 1f;
                    progress.setPercentage(75 + (int)(f * 6)); // 75% -> 81%
                    progress.setSubProgress(curr, total, "Indexing vertices");
                    progress.setDynamicMetrics(
                        "Graph Vertices", String.format("%,d / %,d", curr, total),
                        "Call Edges", "0 / " + totalCallEdges,
                        "Field Links", "Pending",
                        "Caller Triggers", "Pending"
                    );
                } else if ("Call Graph: Mapping Edges".equals(phase)) {
                    float f = totalCallEdges > 0 ? (float) curr / totalCallEdges : 1f;
                    progress.setPercentage(81 + (int)(f * 6)); // 81% -> 87%
                    progress.setSubProgress(curr, totalCallEdges, "Mapping edges");
                    progress.setDynamicMetrics(
                        "Graph Vertices", String.format("%,d", totalMethods),
                        "Call Edges", String.format("%,d / %,d", curr, totalCallEdges),
                        "Field Links", "Pending",
                        "Caller Triggers", "Pending"
                    );
                }
                progress.setCurrentPhase("Call Graph Analysis");
                progress.setMessage(phase);
                progress.setCurrentDetail(detail);
            });

            if (cancelRequested) {
                return;
            }

            // Field Impact Analysis
            progress.setCurrentPhase("Field Impact Analysis");
            progress.setMessage("Indexing field dependencies & propagation…");
            progress.setPercentage(87);
            progress.setCurrentDetail("Querying field relationships from database…");
            progress.setSubProgress(3, 4, "Querying field relationships");
            progress.setDynamicMetrics(
                "Graph Vertices", String.format("%,d", totalMethods),
                "Call Edges", String.format("%,d", totalCallEdges),
                "Field Links", "Querying…",
                "Caller Triggers", "Querying…"
            );

            int totalFieldRels = dao.countFieldRelationships();
            Set<String> callingMethods = callGraph.getCallingMethodFqns();
            int totalCallers = callingMethods.size();

            progress.setCurrentDetail(String.format("Streaming & indexing %,d field relationships across %,d caller methods…", totalFieldRels, totalCallers));
            progress.setSubProgress(4, 4, "Indexing field relations");
            fieldImpact.rebuildWithStream(consumer -> dao.streamFieldRelationships(consumer::accept), totalFieldRels, callingMethods, (phase, curr, total, detail) -> {
                float f = total > 0 ? (float) curr / total : 1f;
                progress.setPercentage(87 + (int)(f * 5)); // 87% -> 92%
                progress.setCurrentDetail(detail);
                progress.setSubProgress(curr, total, "Indexing field relations");
                progress.setDynamicMetrics(
                    "Graph Vertices", String.format("%,d", totalMethods),
                    "Call Edges", String.format("%,d", totalCallEdges),
                    "Field Links", String.format("%,d / %,d", curr, total),
                    "Caller Triggers", String.format("%,d", totalCallers)
                );
            });

            Map<String, String> graphMetrics = new LinkedHashMap<>();
            graphMetrics.put("Graph Vertices", String.format("%,d", totalMethods));
            graphMetrics.put("Call Edges", String.format("%,d", totalCallEdges));
            graphMetrics.put("Field Relations", String.format("%,d", totalFieldRels));
            graphMetrics.put("Caller Triggers", String.format("%,d", totalCallers));
            progress.recordStageEnd("GRAPH", "COMPLETE", String.format("Mapped %,d vertices, %,d call edges & %,d field relationships", totalMethods, totalCallEdges, totalFieldRels), graphMetrics);

            if (cancelRequested) {
                return;
            }

            // Phase 5: Graph Layout Precomputation (Warmup)
            progress.setParsedFiles(result.parsedFiles);
            progress.setErrorFiles(result.errorFiles);
            progress.setTypesFound(result.typesFound);
            progress.setMethodsFound(result.methodsFound);
            progress.setFieldsFound(result.fieldsFound);
            progress.setRelationshipsFound(result.relationshipsFound);

            invalidateGraphCache();
            warmupGraphCache(progress);

            if (cancelRequested) {
                return;
            }

            // Phase 6: Complete
            progress.setActiveStage("COMPLETE");
            progress.setPercentage(100);
            progress.setCurrentPhase("Complete");
            progress.setCurrentDetail("All graphs and indexes precomputed and ready");
            progress.setMessage("Scan complete");
            progress.setEndTime(System.currentTimeMillis());
            progress.setStatus(ScanProgress.Status.COMPLETE);
            progress.setSubProgress(result.totalFiles, result.totalFiles, "Complete");
            progress.setDynamicMetrics(
                "Types", String.format("%,d", result.typesFound),
                "Methods", String.format("%,d", result.methodsFound),
                "Fields", String.format("%,d", result.fieldsFound),
                "Relationships", String.format("%,d", result.relationshipsFound)
            );

            // Persist scan metadata to H2 for instant session restore
            dao.saveScanMeta(progress);

            logProcessBanner("SCAN_COMPLETED", "Full Codebase Scan", sourcePath,
                String.format("Successfully parsed %,d files, %,d types, %,d methods, %,d rels in %d ms",
                    result.parsedFiles, result.typesFound, result.methodsFound, result.relationshipsFound,
                    progress.getEndTime() - progress.getStartTime()));

            log.info("Scan finished: {} types, {} methods, {} fields, {} relationships across {} files ({} parsed, {} errors)",
                result.typesFound, result.methodsFound, result.fieldsFound,
                result.relationshipsFound, result.totalFiles, result.parsedFiles, result.errorFiles);

        } catch (Exception e) {
            log.error("Scan failed", e);
            logProcessBanner("SCAN_FAILED", "Full Codebase Scan", sourcePath, "Error: " + e.getMessage());
            try { db.finishBulkLoad(); } catch (Exception ignored) {}
            progress.recordStageEnd(progress.getActiveStage(), "ERROR", "Scan failed: " + e.getMessage(), null);
            progress.setStatus(ScanProgress.Status.ERROR);
            progress.setMessage("Scan failed");
            progress.setErrorDetail(e.getMessage());
            progress.setEndTime(System.currentTimeMillis());
            try { dao.saveScanMeta(progress); } catch (Exception ignored) {}
        }
    }

    private void runIncrementalScan(String sourcePath, List<String> excludePatterns, ScanProgress progress) {
        cancelRequested = false;
        try {
            try {
                // Self-healing: Ensure all secondary indexes exist before running queries or deletes
                db.ensureSecondaryIndexes();
            } catch (Exception e) {
                log.warn("Secondary index self-healing check: {}", e.getMessage());
            }

            Path root = Paths.get(sourcePath);
            Map<String, FileMeta> existingMeta = dao.getAllFileMeta();
            JavaSourceScanner scanner = new JavaSourceScanner();
            ScanChanges changes = scanner.detectDiskChanges(root, existingMeta, excludePatterns);

            if (!changes.isHasChanges()) {
                progress.setCurrentPhase("Complete");
                progress.setMessage("No changes detected — Codebase is up to date");
                progress.setCurrentDetail("Ready");
                progress.setEndTime(System.currentTimeMillis());
                progress.setStatus(ScanProgress.Status.COMPLETE);
                logProcessBanner("INCREMENTAL_COMPLETED", "Incremental Delta Scan", sourcePath, "No changes detected — codebase is up to date");
                return;
            }

            log.info("Starting incremental delta scan: {} new, {} modified, {} deleted files",
                changes.getNewFiles().size(), changes.getModifiedFiles().size(), changes.getDeletedFiles().size());

            // Phase 1: delete old data for modified and deleted files
            progress.setActiveStage("PREPARE");
            progress.recordStageStart("PREPARE", "Patching Database", "Purging old records for modified and deleted files");
            progress.setCurrentPhase("Patching Database");
            progress.setMessage("Purging old records for modified & deleted files…");
            progress.setPercentage(3);
            List<String> toDelete = new ArrayList<>();
            toDelete.addAll(changes.getModifiedFiles());
            toDelete.addAll(changes.getDeletedFiles());
            dao.deleteBySourceFiles(toDelete);
            progress.recordStageEnd("PREPARE", "COMPLETE", String.format("Purged old records for %d files", toDelete.size()), Map.of("Files Purged", String.valueOf(toDelete.size())));

            // Phase 2: parse modified and new files
            List<Path> toParse = new ArrayList<>();
            for (String p : changes.getNewFiles()) toParse.add(Paths.get(p));
            for (String p : changes.getModifiedFiles()) toParse.add(Paths.get(p));

            progress.setActiveStage("PARSE");
            progress.recordStageStart("PARSE", "Incremental AST Parsing", String.format("Parsing %d changed files in parallel", toParse.size()));
            progress.setCurrentPhase("Incremental AST Parsing");
            progress.setMessage(String.format("Parsing %d changed files…", toParse.size()));

            List<ExcludedScope> excludedScopes = Collections.emptyList();
            try {
                excludedScopes = dao.findAllExcludedScopes();
            } catch (Exception ignored) {}
            final Set<String> excludedTypeFqns = new HashSet<>();
            final Set<String> excludedPkgFqns = new HashSet<>();
            final Set<String> excludedSourceFiles = new HashSet<>();
            for (ExcludedScope s : excludedScopes) {
                if ("PACKAGE".equalsIgnoreCase(s.entityType())) {
                    excludedPkgFqns.add(s.fqn());
                } else {
                    excludedTypeFqns.add(s.fqn());
                    if (s.sourceFile() != null && !s.sourceFile().isBlank()) {
                        excludedSourceFiles.add(s.sourceFile());
                    }
                }
            }

            JavaSourceScanner.ScanResult result = scanner.scanFiles(
                root,
                toParse,
                new JavaSourceScanner.BatchConsumer() {
                    @Override
                    public void onBatch(List<CodePackage> pkgs, List<CodeType> types, List<CodeField> fields,
                                        List<CodeMethod> methods, List<CodeRelationship> rels) throws Exception {
                        onBatch(pkgs, types, fields, methods, rels, Collections.emptyList());
                    }

                    @Override
                    public void onBatch(List<CodePackage> pkgs, List<CodeType> types, List<CodeField> fields,
                                        List<CodeMethod> methods, List<CodeRelationship> rels,
                                        List<FileMeta> fileMetas) throws Exception {
                        FilteredBatch fb = filterExcludedScopeBatch(pkgs, types, fields, methods, rels, fileMetas, excludedTypeFqns, excludedPkgFqns, excludedSourceFiles);
                        dao.batchInsertChunkFast(fb.pkgs, fb.types, fb.fields, fb.methods, fb.rels, fb.fileMetas);
                        lucene.indexBatch(fb.types, fb.methods, fb.fields);
                    }
                },
                new JavaSourceScanner.ProgressCallback() {
                    @Override
                    public void onFile(int done, int total, String file) {
                        onProgress(done, total, file, progress.getTypesFound(), progress.getMethodsFound(), progress.getFieldsFound(), progress.getRelationshipsFound());
                    }

                    @Override
                    public void onProgress(int done, int total, String file, int types, int methods, int fields, int rels) {
                        progress.setTypesFound(types);
                        progress.setMethodsFound(methods);
                        progress.setFieldsFound(fields);
                        progress.setRelationshipsFound(rels);
                        progress.setTotalFiles(total);
                        progress.setProcessedFiles(done);
                        String fileName = file;
                        if (file != null && !file.startsWith("Persisting")) {
                            int lastSlash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
                            fileName = lastSlash >= 0 ? file.substring(lastSlash + 1) : file;
                            progress.setCurrentDetail(fileName);
                            progress.setMessage(String.format("Parsing delta %s (%d/%d)", fileName, done, total));
                        } else if (file != null) {
                            progress.setCurrentDetail("Persisting parsed records to database…");
                            progress.setMessage("Flushing & persisting parsed records…");
                        }
                        float parseFraction = total > 0 ? (float) done / total : 0f;
                        progress.setPercentage(5 + (int) (parseFraction * 65));
                        progress.setSubProgress(done, total, fileName);
                        progress.setDynamicMetrics(
                            "Types", String.format("%,d", types),
                            "Methods", String.format("%,d", methods),
                            "Fields", String.format("%,d", fields),
                            "Relationships", String.format("%,d", rels)
                        );
                    }
                },
                () -> cancelRequested
            );

            if (result.cancelled || cancelRequested) {
                log.info("Incremental scan cancelled for {}", sourcePath);
                logProcessBanner("CANCELLED", "Incremental Delta Scan", sourcePath, "User cancelled incremental scan");
                progress.recordStageEnd(progress.getActiveStage(), "CANCELLED", "Incremental scan cancelled by user", null);
                progress.setActiveStage("COMPLETE");
                progress.setCurrentPhase("Cancelled");
                progress.setCurrentDetail("Incremental scan cancelled by user");
                progress.setMessage("Incremental scan cancelled by user");
                progress.setStatus(ScanProgress.Status.ERROR);
                progress.setErrorDetail("Incremental scan cancelled by user");
                progress.setEndTime(System.currentTimeMillis());
                dao.saveScanMeta(progress);
                return;
            }

            // Phase 3: Save file metadata & recompute package totals
            if (!result.fileMetas.isEmpty()) {
                List<FileMeta> filteredFinal = filterFileMetas(result.fileMetas, excludedPkgFqns, excludedSourceFiles);
                if (!filteredFinal.isEmpty()) {
                    dao.saveFileMetaBatch(filteredFinal);
                }
            }
            dao.recomputePackageCounts();

            Map<String, String> deltaParseMetrics = new LinkedHashMap<>();
            deltaParseMetrics.put("Files Parsed", String.format("%,d", toParse.size()));
            deltaParseMetrics.put("Types Found", String.format("%,d", result.typesFound));
            deltaParseMetrics.put("Methods Found", String.format("%,d", result.methodsFound));
            deltaParseMetrics.put("Fields Found", String.format("%,d", result.fieldsFound));
            deltaParseMetrics.put("Relationships", String.format("%,d", result.relationshipsFound));
            progress.recordStageEnd("PARSE", "COMPLETE", String.format("Parsed %d changed files", toParse.size()), deltaParseMetrics);

            // Phase 4: finish Lucene commit & rebuild in-memory graphs
            progress.setActiveStage("INDEX");
            progress.recordStageStart("INDEX", "Updating Search Index", "Committing incremental search index & verifying database indexes");
            progress.setCurrentPhase("Updating Search Index");
            progress.setMessage("Committing incremental search index…");
            progress.setCurrentDetail("Committing Lucene index to disk…");
            progress.setPercentage(72);
            progress.setSubProgress(1, 1, "Lucene Delta Index");
            progress.setDynamicMetrics("Lucene Delta", String.format("%,d files", toParse.size()), "DB Status", "Ready", "Target", "lucene-index", "State", "Committing");
            lucene.finishIndexRebuild();
            try {
                db.ensureSecondaryIndexes();
            } catch (Exception ignored) {}
            Map<String, String> deltaIndexMetrics = new LinkedHashMap<>();
            deltaIndexMetrics.put("Lucene Delta", String.format("%,d files", toParse.size()));
            deltaIndexMetrics.put("DB Indexes", "Verified");
            deltaIndexMetrics.put("Search Index", "Committed");
            deltaIndexMetrics.put("Storage Engine", "H2 MVStore");
            progress.recordStageEnd("INDEX", "COMPLETE", "Incremental search index committed and DB indexes verified", deltaIndexMetrics);

            if (cancelRequested) return;

            progress.setActiveStage("GRAPH");
            progress.recordStageStart("GRAPH", "Call Graph Analysis & Field Propagation", "Refreshing call graph and field impact models");
            progress.setCurrentPhase("Call Graph Analysis");
            progress.setMessage("Refreshing call graph & topology…");
            progress.setPercentage(75);
            progress.setSubProgress(1, 4, "Querying methods from storage");
            progress.setDynamicMetrics("Graph Vertices", "Querying…", "Call Edges", "Pending", "Field Links", "Pending", "Caller Triggers", "Pending");

            List<String> allMethodFqns = dao.findAllMethodFqns();
            int totalMethods = allMethodFqns.size();
            progress.setCurrentDetail(String.format("Fetched %,d methods from storage", totalMethods));
            progress.setSubProgress(1, 4, "Querying call pairs from storage");
            progress.setDynamicMetrics("Graph Vertices", String.format("%,d loaded", totalMethods), "Call Edges", "Querying…", "Field Links", "Pending", "Caller Triggers", "Pending");

            int totalCallEdges = dao.countCallRelationships();
            progress.setCurrentDetail(String.format("Found %,d call relationships; building graph vertices…", totalCallEdges));
            progress.setSubProgress(2, 4, "Mapping call graph");

            callGraph.rebuild(allMethodFqns, consumer -> dao.streamCallRelationships(consumer::accept), (phase, curr, total, detail) -> {
                if ("Call Graph: Indexing Methods".equals(phase)) {
                    float f = total > 0 ? (float) curr / total : 1f;
                    progress.setPercentage(75 + (int)(f * 6));
                    progress.setSubProgress(curr, total, "Indexing vertices");
                    progress.setDynamicMetrics(
                        "Graph Vertices", String.format("%,d / %,d", curr, total),
                        "Call Edges", "0 / " + totalCallEdges,
                        "Field Links", "Pending",
                        "Caller Triggers", "Pending"
                    );
                } else if ("Call Graph: Mapping Edges".equals(phase)) {
                    float f = totalCallEdges > 0 ? (float) curr / totalCallEdges : 1f;
                    progress.setPercentage(81 + (int)(f * 6));
                    progress.setSubProgress(curr, totalCallEdges, "Mapping edges");
                    progress.setDynamicMetrics(
                        "Graph Vertices", String.format("%,d", totalMethods),
                        "Call Edges", String.format("%,d / %,d", curr, totalCallEdges),
                        "Field Links", "Pending",
                        "Caller Triggers", "Pending"
                    );
                }
                progress.setCurrentPhase("Call Graph Analysis");
                progress.setMessage(phase);
                progress.setCurrentDetail(detail);
            });

            if (cancelRequested) return;

            progress.setCurrentPhase("Field Impact Analysis");
            progress.setMessage("Indexing field dependencies & propagation…");
            progress.setPercentage(87);
            progress.setSubProgress(3, 4, "Querying field relationships");
            progress.setDynamicMetrics(
                "Graph Vertices", String.format("%,d", totalMethods),
                "Call Edges", String.format("%,d", totalCallEdges),
                "Field Links", "Querying…",
                "Caller Triggers", "Querying…"
            );

            int totalFieldRels = dao.countFieldRelationships();
            Set<String> callingMethods = callGraph.getCallingMethodFqns();
            int totalCallers = callingMethods.size();

            progress.setCurrentDetail(String.format("Streaming & indexing %,d field relationships across %,d caller methods…", totalFieldRels, totalCallers));
            progress.setSubProgress(4, 4, "Indexing field relations");
            fieldImpact.rebuildWithStream(consumer -> dao.streamFieldRelationships(consumer::accept), totalFieldRels, callingMethods, (phase, curr, total, detail) -> {
                float f = total > 0 ? (float) curr / total : 1f;
                progress.setPercentage(87 + (int)(f * 5));
                progress.setCurrentDetail(detail);
                progress.setSubProgress(curr, total, "Indexing field relations");
                progress.setDynamicMetrics(
                    "Graph Vertices", String.format("%,d", totalMethods),
                    "Call Edges", String.format("%,d", totalCallEdges),
                    "Field Links", String.format("%,d / %,d", curr, total),
                    "Caller Triggers", String.format("%,d", totalCallers)
                );
            });

            Map<String, String> deltaGraphMetrics = new LinkedHashMap<>();
            deltaGraphMetrics.put("Graph Vertices", String.format("%,d", totalMethods));
            deltaGraphMetrics.put("Call Edges", String.format("%,d", totalCallEdges));
            deltaGraphMetrics.put("Field Relations", String.format("%,d", totalFieldRels));
            progress.recordStageEnd("GRAPH", "COMPLETE", String.format("Refreshed graph with %,d vertices and %,d call edges", totalMethods, totalCallEdges), deltaGraphMetrics);

            if (cancelRequested) return;

            // Recompute scan totals from DB
            Map<String, Object> stats = dao.getStats();
            int totalTypes = stats.containsKey("types") ? ((Number) stats.get("types")).intValue() : 0;
            int totalMethodsCount = stats.containsKey("methods") ? ((Number) stats.get("methods")).intValue() : 0;
            int totalFieldsCount = stats.containsKey("fields") ? ((Number) stats.get("fields")).intValue() : 0;
            int totalRelsCount = stats.containsKey("relationships") ? ((Number) stats.get("relationships")).intValue() : 0;

            Map<String, FileMeta> allMeta = dao.getAllFileMeta();
            progress.setTotalFiles(allMeta.size());
            progress.setProcessedFiles(allMeta.size());
            progress.setParsedFiles(allMeta.size());
            progress.setErrorFiles(result.errorFiles);
            progress.setTypesFound(totalTypes);
            progress.setMethodsFound(totalMethodsCount);
            progress.setFieldsFound(totalFieldsCount);
            progress.setRelationshipsFound(totalRelsCount);

            // Phase 5: Invalidate obsolete cached graph layouts and warm up fresh ones
            invalidateGraphCache();
            warmupGraphCache(progress);

            if (cancelRequested) return;

            // Phase 6: Complete
            progress.setActiveStage("COMPLETE");
            progress.setCurrentPhase("Complete");
            progress.setCurrentDetail("Ready");
            progress.setMessage("Incremental scan complete");
            progress.setPercentage(100);
            progress.setEndTime(System.currentTimeMillis());
            progress.setStatus(ScanProgress.Status.COMPLETE);
            progress.setSubProgress(allMeta.size(), allMeta.size(), "Complete");
            progress.setDynamicMetrics(
                "Types", String.format("%,d", totalTypes),
                "Methods", String.format("%,d", totalMethodsCount),
                "Fields", String.format("%,d", totalFieldsCount),
                "Relationships", String.format("%,d", totalRelsCount)
            );

            dao.saveScanMeta(progress);

            logProcessBanner("INCREMENTAL_COMPLETED", "Incremental Delta Scan", sourcePath,
                String.format("Parsed %d changed files; total codebase is now %,d types and %,d methods in %d ms",
                    toParse.size(), totalTypes, totalMethodsCount,
                    progress.getEndTime() - progress.getStartTime()));

            log.info("Incremental delta scan finished: parsed {} changed files, total indexed is now {} types, {} methods across {} files",
                toParse.size(), totalTypes, totalMethodsCount, allMeta.size());

        } catch (Exception e) {
            log.error("Incremental scan failed", e);
            logProcessBanner("INCREMENTAL_FAILED", "Incremental Delta Scan", sourcePath, "Error: " + e.getMessage());
            progress.recordStageEnd(progress.getActiveStage(), "ERROR", "Incremental scan failed: " + e.getMessage(), null);
            progress.setStatus(ScanProgress.Status.ERROR);
            progress.setMessage("Incremental scan failed");
            progress.setErrorDetail(e.getMessage());
            progress.setEndTime(System.currentTimeMillis());
            try { dao.saveScanMeta(progress); } catch (Exception ignored) {}
        }
    }





    // ─────────────────────────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────────────────────────
    private void getStats(Context ctx) throws Exception {
        Map<String, Object> stats = dao.getStats();
        stats.put("methodsList", dao.findMethodSignatures());
        stats.put("typesList", dao.findTypeSignatures());
        stats.put("persistentClasses", dao.findPersistentClassFqns());
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

    private void getModuleDependencies(Context ctx) throws Exception {
        String name = decode(ctx.pathParam("name"));
        serveModuleDependencies(ctx, name);
    }

    private void getPackageDependencies(Context ctx) throws Exception {
        String fqn = decode(ctx.pathParam("fqn"));
        serveModuleDependencies(ctx, fqn);
    }

    private void serveModuleDependencies(Context ctx, String query) throws Exception {
        if (query == null || query.isBlank()) {
            ctx.status(400).json(Map.of("error", "Module or package parameter is required"));
            return;
        }

        String cacheKey = query.trim().toLowerCase();
        ModuleDependencyAnalyzer.ModuleDependencyInsights cached = moduleDependencyCache.get(cacheKey);
        if (cached != null) {
            ctx.json(cached);
            return;
        }

        ScanProgress sp = scanState.get();
        if (sp != null && sp.getStatus() == ScanProgress.Status.SCANNING) {
            ctx.status(202).json(Map.of("status", "scanning", "message", "Scan in progress, module dependency analysis pending"));
            return;
        }

        List<CodePackage> packages = dao.findAllPackages();
        List<CodeType> types = dao.findAllTypes();
        List<CodeMethod> methods = dao.findAllMethods();
        List<CodeField> fields = dao.findAllFields();
        List<CodeRelationship> relationships = (callGraph != null && callGraph.getCallGraph() != null)
            ? dao.findNonCallRelationships()
            : dao.findAllRelationships();

        ModuleDependencyAnalyzer.ModuleDependencyInsights insights = moduleDependencyAnalyzer.analyzeModule(
            query, packages, types, methods, fields, relationships, callGraph
        );

        if (insights == null) {
            ctx.status(404).json(Map.of("error", "Module or package not found: " + query));
            return;
        }

        moduleDependencyCache.put(cacheKey, insights);
        if (insights.moduleName != null) {
            moduleDependencyCache.put(insights.moduleName.toLowerCase(), insights);
        }
        if (insights.packageFqn != null) {
            moduleDependencyCache.put(insights.packageFqn.toLowerCase(), insights);
        }

        ctx.json(insights);
    }

    private void getAllModuleDependencies(Context ctx) throws Exception {
        ModuleDependencyAnalyzer.ModuleOverviewPayload overview = cachedModuleOverview;
        if (overview != null) {
            ctx.json(overview);
            return;
        }

        ScanProgress sp = scanState.get();
        if (sp != null && sp.getStatus() == ScanProgress.Status.SCANNING) {
            ctx.status(202).json(Map.of("status", "scanning", "message", "Scan in progress, module dependency overview pending"));
            return;
        }

        List<CodePackage> packages = dao.findAllPackages();
        List<CodeType> types = dao.findAllTypes();
        List<CodeMethod> methods = dao.findAllMethods();
        List<CodeField> fields = dao.findAllFields();
        List<CodeRelationship> relationships = (callGraph != null && callGraph.getCallGraph() != null)
            ? dao.findNonCallRelationships()
            : dao.findAllRelationships();

        overview = moduleDependencyAnalyzer.analyzeAll(packages, types, methods, fields, relationships, callGraph);
        cachedModuleOverview = overview;
        ctx.json(overview);
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

        String methodFqn = m.get().getFqn();
        int callers = (callGraph != null) ? callGraph.callerCount(methodFqn) : 0;
        int callees = (callGraph != null) ? callGraph.calleeCount(methodFqn) : 0;
        detail.put("callerCount", callers);
        detail.put("calleeCount", callees);
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

    private void getHubExplorer(Context ctx) throws Exception {
        String fqn = ctx.queryParam("fqn");
        if (fqn == null || fqn.trim().isEmpty()) {
            ctx.status(400).result("Missing 'fqn' query parameter");
            return;
        }
        String direction = ctx.queryParam("direction"); // "callers", "callees", or "both"
        ctx.json(callGraph.hubExplorerView(fqn, direction));
    }

    private void getCallGraph(Context ctx) throws Exception {
        String id    = decode(ctx.pathParam("id"));
        int    depth = intParam(ctx, "depth", 3);
        boolean hideGetters = Boolean.parseBoolean(ctx.queryParam("hideGetters"));
        String key = "hierarchy:" + id + ":" + depth + ":" + hideGetters;
        ctx.json(getOrComputeLayout(key, () -> callGraph.callHierarchyView(id, depth, hideGetters)));
    }

    private void getFullGraph(Context ctx) throws Exception {
        boolean hideGetters = Boolean.parseBoolean(ctx.queryParam("hideGetters"));
        // Return raw graph (no precomputed x,y) so the client-side blooming tree
        // layout + physics simulation produces the tree-like clustered visualization
        // with distinct colors per package branch.
        String key = "full-raw:" + hideGetters;
        if (handleConditionalETag(ctx, key)) {
            return;
        }
        ctx.json(getOrComputeLayout(key, () -> callGraph.fullGraphView(hideGetters)));
    }

    private void getArchitectureGraph(Context ctx) throws Exception {
        String scope  = ctx.queryParam("scope");
        String filter = ctx.queryParam("filter");
        // Return raw graph (no precomputed x,y) so the client-side blooming tree
        // layout + physics simulation produces the tree-like clustered visualization.
        String key = "arch-raw:" + (scope != null ? scope : "classes") + ":" + (filter != null ? filter : "none");
        if (handleConditionalETag(ctx, key)) {
            return;
        }
        ctx.json(getOrComputeLayout(key, () -> callGraph.architectureGraphView(scope, filter)));
    }

    private void getPrecomputedGraph(Context ctx) throws Exception {
        String scope  = ctx.queryParam("scope");
        String filter = ctx.queryParam("filter");
        boolean hideGetters = Boolean.parseBoolean(ctx.queryParam("hideGetters"));
        String key = ("all".equalsIgnoreCase(scope) || "methods".equalsIgnoreCase(scope))
            ? "full:" + hideGetters
            : "arch:" + (scope != null ? scope : "classes") + ":" + (filter != null ? filter : "none");
        if (handleConditionalETag(ctx, key)) {
            return;
        }
        if ("all".equalsIgnoreCase(scope) || "methods".equalsIgnoreCase(scope)) {
            ctx.json(getOrComputeLayout(key, () -> callGraph.precomputedFullGraphView(hideGetters)));
        } else {
            ctx.json(getOrComputeLayout(key, () -> callGraph.precomputedArchitectureGraphView(scope, filter)));
        }
    }

    private void exportGraphJson(Context ctx) throws Exception {
        String scope  = ctx.queryParam("scope");
        String filter = ctx.queryParam("filter");
        boolean hideGetters = Boolean.parseBoolean(ctx.queryParam("hideGetters"));
        CallGraphAnalyzer.GraphView view;
        if ("all".equalsIgnoreCase(scope) || "methods".equalsIgnoreCase(scope)) {
            String key = "full:" + hideGetters;
            view = getOrComputeLayout(key, () -> callGraph.precomputedFullGraphView(hideGetters));
        } else {
            String key = "arch:" + (scope != null ? scope : "classes") + ":" + (filter != null ? filter : "none");
            view = getOrComputeLayout(key, () -> callGraph.precomputedArchitectureGraphView(scope, filter));
        }
        String safeScope = (scope == null || scope.isBlank()) ? "architecture" : scope.replaceAll("[^a-zA-Z0-9_-]", "_");
        ctx.header("Content-Disposition", "attachment; filename=\"codelens-" + safeScope + "-graph.json\"")
           .json(view);
    }

    private void getDSM(Context ctx) throws Exception {
        String scope  = ctx.queryParam("scope");
        String filter = ctx.queryParam("filter");
        String format = ctx.queryParam("format");
        CallGraphAnalyzer.DSMPayload dsm = callGraph.dsmView(scope, filter);

        if ("csv".equalsIgnoreCase(format)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Caller/Callee");
            for (String c : dsm.classes) {
                sb.append(",\"").append(c.replace("\"", "\"\"")).append("\"");
            }
            sb.append("\n");
            for (int r = 0; r < dsm.classes.size(); r++) {
                sb.append("\"").append(dsm.classes.get(r).replace("\"", "\"\"")).append("\"");
                for (int c = 0; c < dsm.classes.size(); c++) {
                    int val = (dsm.matrix != null && r < dsm.matrix.length && c < dsm.matrix[r].length)
                        ? dsm.matrix[r][c] : 0;
                    sb.append(",").append(val);
                }
                sb.append("\n");
            }
            String safeScope = (scope != null && !scope.isBlank()) ? scope : "dsm";
            ctx.contentType("text/csv; charset=UTF-8")
               .header("Content-Disposition", "attachment; filename=\"codelens-" + safeScope + "-matrix.csv\"")
               .result(sb.toString());
        } else {
            ctx.json(dsm);
        }
    }

    private void getTreemap(Context ctx) throws Exception {
        String scope  = ctx.queryParam("scope");
        String filter = ctx.queryParam("filter");
        String key = "treemap:" + (scope != null ? scope : "all") + ":" + (filter != null ? filter : "none");
        if (handleConditionalETag(ctx, key)) {
            return;
        }
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

    private void getPersistentClasses(Context ctx) throws Exception {
        List<CodeType> types = dao.findAllTypes();
        List<CodeMethod> methods = dao.findAllMethods();
        List<CriticalPathAnalyzer.PersistentClassSummary> summaries =
            criticalPathAnalyzer.findPersistentClasses(types, methods);
        ctx.json(summaries);
    }

    private void getCriticalPath(Context ctx) throws Exception {
        String classFqn = ctx.queryParam("class");
        if (classFqn == null || classFqn.isBlank()) {
            classFqn = ctx.queryParam("id");
        }
        if (classFqn == null || classFqn.isBlank()) {
            ctx.status(400).json(Map.of("error", "Query parameter 'class' is required"));
            return;
        }

        List<CodeType> types = dao.findAllTypes();
        List<CodeMethod> methods = dao.findAllMethods();

        Map<String, CodeMethod> methodMap = new HashMap<>(methods.size());
        for (CodeMethod m : methods) {
            methodMap.put(m.getFqn(), m);
        }

        Map<String, CodeType> typeMap = new HashMap<>(types.size());
        for (CodeType t : types) {
            typeMap.put(t.getFqn(), t);
        }

        CriticalPathAnalyzer.CriticalPathReport report =
            criticalPathAnalyzer.analyzeCriticalPaths(classFqn, callGraph.getCallGraph(), methodMap, typeMap, true);

        String mode = ctx.queryParam("mode");
        if (mode != null && !mode.isBlank() && report.candidatePaths != null) {
            String mLower = mode.trim().toLowerCase();
            // Support aliases
            if (mLower.equals("critical")) mLower = "primary";
            else if (mLower.equals("fastest")) mLower = "read";
            else if (mLower.equals("deepest")) mLower = "longest";
            else if (mLower.equals("fanout") || mLower.equals("bottleneck")) mLower = "max_complexity";
            else if (mLower.equals("db_heavy")) mLower = "mutation";

            for (CriticalPathAnalyzer.CriticalPath cp : report.candidatePaths) {
                if (mLower.equalsIgnoreCase(cp.pathId) || mLower.equalsIgnoreCase(cp.category)) {
                    report.primaryPath = cp;
                    break;
                }
            }
        }

        ctx.json(report);
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
        List<Map<String, Object>> hotEntities = dao.findHottestEntities(500);

        // Enrich hotEntities with AST cyclomatic complexity & compute behavioral hotspot score
        List<CodeMethod> allMethods = dao.findAllMethods();
        Map<String, Integer> methodCC = new HashMap<>();
        Map<String, Integer> classCC = new HashMap<>();
        for (CodeMethod m : allMethods) {
            int cc = Math.max(1, m.getCyclomaticComplexity());
            methodCC.put(m.getFqn(), cc);
            if (m.getDeclaringTypeFqn() != null) {
                classCC.merge(m.getDeclaringTypeFqn(), cc, Integer::sum);
            }
        }

        List<Map<String, Object>> behavioralHotspots = new ArrayList<>();
        for (Map<String, Object> e : hotEntities) {
            String fqn = (String) e.get("entityFqn");
            int commits = ((Number) e.getOrDefault("commitCount", 1)).intValue();
            int cc = methodCC.getOrDefault(fqn, classCC.getOrDefault(fqn, 1));
            double raw = cc * (Math.log(1 + commits) / Math.log(2.0));
            int score = (int) Math.min(100, Math.max(1, Math.round(raw * 3.0)));

            e.put("cyclomaticComplexity", cc);
            e.put("hotspotScore", score);
            e.put("riskTier", score >= 70 ? "CRITICAL" : (score >= 45 ? "HIGH" : (score >= 25 ? "MEDIUM" : "LOW")));

            if (score >= 25 || commits >= 3) {
                behavioralHotspots.add(e);
            }
        }

        behavioralHotspots.sort((a, b) -> Integer.compare(
            ((Number) b.getOrDefault("hotspotScore", 0)).intValue(),
            ((Number) a.getOrDefault("hotspotScore", 0)).intValue()
        ));

        summary.put("hotEntities", hotEntities);
        summary.put("behavioralHotspots", behavioralHotspots);
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

    private void triggerGitAnalysis(String canonicalRepoPath, String branch) {
        GitAnalysisProgress initial = new GitAnalysisProgress(GitAnalysisProgress.Status.RUNNING);
        initial.setRepoPath(canonicalRepoPath);
        initial.setBranch(branch);
        initial.setStartTime(System.currentTimeMillis());
        initial.setMessage("Preparing Git history analysis…");
        gitProgress.set(initial);
        logProcessBanner("GIT_ANALYSIS_STARTED", "Git Churn & Hotspot Analyzer", canonicalRepoPath,
            "Starting background Git blame & churn analysis" + (branch != null ? " (branch: " + branch + ")" : ""));

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
                GitAnalysisProgress p = gitProgress.get();
                int totalFiles = p != null ? p.getTotalFiles() : 0;
                logProcessBanner("GIT_ANALYSIS_COMPLETED", "Git Churn & Hotspot Analyzer", canonicalRepoPath,
                    String.format("Git analysis complete — %,d entities annotated across %,d files", gitMetas.size(), totalFiles));
                log.info("Background Git analysis completed: {} entities annotated", gitMetas.size());

                GitAnalysisProgress completed = gitProgress.get();
                if (completed != null) {
                    completed.setStatus(GitAnalysisProgress.Status.COMPLETE);
                    completed.setEntitiesAnnotated(gitMetas.size());
                    completed.setEndTime(System.currentTimeMillis());
                    completed.setMessage("Git analysis complete — " + gitMetas.size() + " entities annotated.");
                }
            } catch (Exception e) {
                logProcessBanner("GIT_ANALYSIS_FAILED", "Git Churn & Hotspot Analyzer", canonicalRepoPath, "Error: " + e.getMessage());
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

        triggerGitAnalysis(validation.getRepoPath(), validation.getBranch());
        ctx.json(Map.of("status", "started", "repoPath", validation.getRepoPath(), "branch", validation.getBranch()));
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
            Object fullGraph = callGraph.precomputedFullGraphView(false);
            Object archGraph = callGraph.precomputedArchitectureGraphView(scope, filter);

            String projectName = types.isEmpty() ? "Codebase"
                : (types.get(0).getPackageFqn() != null && !types.get(0).getPackageFqn().isBlank() ? types.get(0).getPackageFqn() : "Codebase");

            String html = reportService.generateInteractiveHtmlSnapshot(projectName, fullGraph, archGraph, archData);
            ctx.contentType("text/html; charset=UTF-8").result(html);
        } catch (Exception e) {
            log.error("Failed to generate HTML graph snapshot: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to generate HTML graph snapshot: " + e.getMessage()));
        }
    }

    private void getChangeRiskReport(Context ctx) {
        try {
            String format = ctx.queryParam("format");
            if (format == null || format.isBlank()) format = "json";
            else format = format.trim().toLowerCase();

            List<CodeType> types = dao.findAllTypes();
            List<CodeMethod> methods = dao.findAllMethods();
            List<CodeField> fields = dao.findAllFields();
            List<CodeRelationship> rels = dao.findAllRelationships();
            List<GitMeta> gitMetas = dao.findAllGitMeta();

            ReportService.ChangeRiskReportData data = reportService.buildChangeRiskData(types, methods, fields, rels, gitMetas);

            if ("html".equals(format)) {
                ctx.contentType("text/html; charset=UTF-8").result(reportService.renderChangeRiskHtml(data));
            } else if ("markdown".equals(format) || "md".equals(format)) {
                ctx.contentType("text/markdown; charset=UTF-8").result(reportService.renderChangeRiskMarkdown(data));
            } else if ("csv".equals(format)) {
                ctx.contentType("text/csv; charset=UTF-8").result(reportService.renderChangeRiskCsv(data));
            } else {
                ctx.json(data);
            }
        } catch (Exception e) {
            log.error("Failed to generate change risk report: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to generate change risk report: " + e.getMessage()));
        }
    }

    private void getDeadCodeReport(Context ctx) {
        try {
            String format = ctx.queryParam("format");
            if (format == null || format.isBlank()) format = "json";
            else format = format.trim().toLowerCase();

            List<CodeType> types = dao.findAllTypes();
            List<CodeMethod> methods = dao.findAllMethods();
            List<CodeField> fields = dao.findAllFields();
            List<CodeRelationship> rels = dao.findAllRelationships();

            ReportService.DeadCodeReportData data = reportService.buildDeadCodeData(types, methods, fields, rels);

            if ("html".equals(format)) {
                ctx.contentType("text/html; charset=UTF-8").result(reportService.renderDeadCodeHtml(data));
            } else if ("markdown".equals(format) || "md".equals(format)) {
                ctx.contentType("text/markdown; charset=UTF-8").result(reportService.renderDeadCodeMarkdown(data));
            } else if ("csv".equals(format)) {
                ctx.contentType("text/csv; charset=UTF-8").result(reportService.renderDeadCodeCsv(data));
            } else {
                ctx.json(data);
            }
        } catch (Exception e) {
            log.error("Failed to generate dead code report: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to generate dead code report: " + e.getMessage()));
        }
    }

    private void getCircularDependenciesReport(Context ctx) {
        try {
            String format = ctx.queryParam("format");
            if (format == null || format.isBlank()) format = "json";
            else format = format.trim().toLowerCase();

            List<CodeType> types = dao.findAllTypes();
            List<CodeMethod> methods = dao.findAllMethods();
            List<CodeRelationship> rels = dao.findAllRelationships();

            ReportService.CircularDependencyReportData data = reportService.buildCircularDependencyData(types, methods, rels);

            if ("html".equals(format)) {
                ctx.contentType("text/html; charset=UTF-8").result(reportService.renderCircularDependencyHtml(data));
            } else if ("markdown".equals(format) || "md".equals(format)) {
                ctx.contentType("text/markdown; charset=UTF-8").result(reportService.renderCircularDependencyMarkdown(data));
            } else if ("csv".equals(format)) {
                ctx.contentType("text/csv; charset=UTF-8").result(reportService.renderCircularDependencyCsv(data));
            } else {
                ctx.json(data);
            }
        } catch (Exception e) {
            log.error("Failed to generate circular dependencies report: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to generate circular dependencies report: " + e.getMessage()));
        }
    }

    private void getArchetypeGovernanceReport(Context ctx) {
        try {
            String format = ctx.queryParam("format");
            if (format == null || format.isBlank()) format = "json";
            else format = format.trim().toLowerCase();

            List<CodeType> types = dao.findAllTypes();
            List<CodeMethod> methods = dao.findAllMethods();
            List<CodeField> fields = dao.findAllFields();
            List<CodeRelationship> rels = dao.findAllRelationships();

            ReportService.ArchetypeGovernanceReportData data = reportService.buildArchetypeGovernanceData(types, methods, fields, rels);

            if ("html".equals(format)) {
                ctx.contentType("text/html; charset=UTF-8").result(reportService.renderArchetypeGovernanceHtml(data));
            } else if ("markdown".equals(format) || "md".equals(format)) {
                ctx.contentType("text/markdown; charset=UTF-8").result(reportService.renderArchetypeGovernanceMarkdown(data));
            } else if ("csv".equals(format)) {
                ctx.contentType("text/csv; charset=UTF-8").result(reportService.renderArchetypeGovernanceCsv(data));
            } else {
                ctx.json(data);
            }
        } catch (Exception e) {
            log.error("Failed to generate archetype governance report: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to generate archetype governance report: " + e.getMessage()));
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
            } else if ("change-risk".equals(type)) {
                getChangeRiskReport(ctx);
            } else if ("dead-code".equals(type)) {
                getDeadCodeReport(ctx);
            } else if ("circular-dependencies".equals(type) || "cycles".equals(type)) {
                getCircularDependenciesReport(ctx);
            } else if ("archetype-governance".equals(type) || "governance".equals(type)) {
                getArchetypeGovernanceReport(ctx);
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
        if (config != null) {
            CallGraphAnalyzer.setCustomPojoPatterns(config.getPojoCustomPatterns());
        }
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
            if (updated != null) {
                CallGraphAnalyzer.setCustomPojoPatterns(updated.getPojoCustomPatterns());
                invalidateGraphCache();
            }
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
            if (imported != null) {
                CallGraphAnalyzer.setCustomPojoPatterns(imported.getPojoCustomPatterns());
                invalidateGraphCache();
            }
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
            CallGraphAnalyzer.setCustomPojoPatterns(activeConfig.getPojoCustomPatterns());
            invalidateGraphCache();
            File targetFile = activeConfigFile != null ? activeConfigFile : new File("./codelens.conf");
            activeConfig.saveToFile(targetFile);
            ctx.json(Map.of("status", "ok", "message", "Configuration reset to factory defaults", "config", activeConfig));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to save reset configuration: " + e.getMessage()));
        }
    }

    private void getReadme(Context ctx) {
        try {
            // First check for local README.md in development or current working directory
            File localReadme = new File("README.md");
            if (localReadme.exists() && localReadme.isFile()) {
                ctx.contentType("text/markdown; charset=utf-8").result(Files.readString(localReadme.toPath()));
                return;
            }
            // Fallback to classpath inside the packaged fat JAR
            try (InputStream in = getClass().getResourceAsStream("/README.md")) {
                if (in != null) {
                    ctx.contentType("text/markdown; charset=utf-8").result(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    return;
                }
            }
            try (InputStream in = getClass().getResourceAsStream("/web/README.md")) {
                if (in != null) {
                    ctx.contentType("text/markdown; charset=utf-8").result(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    return;
                }
            }
            ctx.status(404).contentType("text/markdown; charset=utf-8").result("# README.md not found");
        } catch (Exception e) {
            ctx.status(500).contentType("text/markdown; charset=utf-8").result("# Error reading README: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scope Management Handlers
    // ─────────────────────────────────────────────────────────────────────────
    private void excludeScope(Context ctx) {
        try {
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String type = body.get("type") != null ? body.get("type").toString() : "CLASS";
            String fqn = body.get("fqn") != null ? body.get("fqn").toString() : null;
            if (fqn == null || fqn.isBlank()) {
                ctx.status(400).json(Map.of("error", "fqn is required"));
                return;
            }
            fqn = fqn.trim();

            ScanProgress current = scanState.get();
            if (current != null && current.getStatus() == ScanProgress.Status.SCANNING) {
                ctx.status(409).json(Map.of("error", "Cannot exclude scope while a scan is in progress"));
                return;
            }

            if ("PACKAGE".equalsIgnoreCase(type)) {
                dao.excludePackage(fqn);
                lucene.deletePackageFromIndex(fqn);
                log.info("Excluded package from scope: {}", fqn);
            } else {
                dao.excludeType(fqn);
                lucene.deleteTypeFromIndex(fqn);
                log.info("Excluded class from scope: {}", fqn);
            }

            // Invalidate layout cache and rebuild in-memory call graph and field impact
            invalidateGraphCache();
            List<String> allMethodFqns = dao.findAllMethodFqns();
            callGraph.rebuild(allMethodFqns, consumer -> dao.streamCallRelationships(consumer::accept));
            int totalFieldRels = dao.countFieldRelationships();
            fieldImpact.rebuildWithStream(consumer -> dao.streamFieldRelationships(consumer::accept), totalFieldRels, callGraph.getCallingMethodFqns());

            ctx.json(Map.of(
                "success", true,
                "fqn", fqn,
                "type", type.toUpperCase(),
                "excluded", dao.findAllExcludedScopes()
            ));
        } catch (Exception e) {
            log.error("Failed to exclude scope: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    private void getExcludedScopes(Context ctx) {
        try {
            List<ExcludedScope> excluded = dao.findAllExcludedScopes();
            ctx.json(excluded);
        } catch (Exception e) {
            log.error("Failed to get excluded scopes: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    private void restoreScope(Context ctx) {
        try {
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String fqn = (String) body.get("fqn");
            if (fqn == null || fqn.isBlank()) {
                ctx.status(400).json(Map.of("error", "fqn is required"));
                return;
            }
            fqn = fqn.trim();

            ScanProgress current = scanState.get();
            if (current != null && current.getStatus() == ScanProgress.Status.SCANNING) {
                ctx.status(409).json(Map.of("error", "Cannot restore scope while a scan is in progress"));
                return;
            }

            Optional<ExcludedScope> scopeOpt = dao.findExcludedScopeByFqn(fqn);
            boolean removed = dao.restoreScope(fqn);

            String sourceFile = "";
            if (scopeOpt.isPresent()) {
                ExcludedScope s = scopeOpt.get();
                sourceFile = s.sourceFile();
                if ("PACKAGE".equalsIgnoreCase(s.entityType())) {
                    try {
                        dao.deleteFileMetaForPackage(fqn);
                    } catch (Exception e) {
                        log.warn("Failed to delete file_meta for package {}: {}", fqn, e.getMessage());
                    }
                } else if (sourceFile != null && !sourceFile.isBlank()) {
                    try {
                        dao.deleteFileMeta(sourceFile);
                    } catch (Exception e) {
                        log.warn("Failed to delete file_meta for file {}: {}", sourceFile, e.getMessage());
                    }
                }
            }

            try {
                dao.cleanupOrphanFileMeta();
            } catch (Exception e) {
                log.warn("Failed to cleanup orphan file_meta during restore: {}", e.getMessage());
            }

            String sourcePath = resolveCurrentSourcePath();
            List<String> excludePatterns = resolveCurrentExcludePatterns();
            if (sourcePath != null && !sourcePath.isBlank()) {
                ScanProgress progress = new ScanProgress(ScanProgress.Status.SCANNING);
                progress.setSourcePath(sourcePath);
                progress.setCurrentPhase("Delta Change Detection");
                progress.setMessage("Restoring " + fqn + " and re-indexing…");
                progress.setStartTime(System.currentTimeMillis());
                scanState.set(progress);
                runIncrementalScan(sourcePath, excludePatterns, progress);
            }

            ctx.json(Map.of(
                "success", removed,
                "fqn", fqn,
                "sourceFile", sourceFile != null ? sourceFile : "",
                "remainingExcluded", dao.findAllExcludedScopes()
            ));
        } catch (Exception e) {
            log.error("Failed to restore scope: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    private void clearExcludedScopes(Context ctx) {
        try {
            ScanProgress current = scanState.get();
            if (current != null && current.getStatus() == ScanProgress.Status.SCANNING) {
                ctx.status(409).json(Map.of("error", "Cannot clear excluded scopes while a scan is in progress"));
                return;
            }

            dao.clearAllExcludedScopes();
            try {
                dao.cleanupOrphanFileMeta();
            } catch (Exception e) {
                log.warn("Failed to cleanup orphan file_meta during clear all scopes: {}", e.getMessage());
            }

            String sourcePath = resolveCurrentSourcePath();
            List<String> excludePatterns = resolveCurrentExcludePatterns();
            if (sourcePath != null && !sourcePath.isBlank()) {
                ScanProgress progress = new ScanProgress(ScanProgress.Status.SCANNING);
                progress.setSourcePath(sourcePath);
                progress.setCurrentPhase("Delta Change Detection");
                progress.setMessage("Restoring all scopes and re-indexing…");
                progress.setStartTime(System.currentTimeMillis());
                scanState.set(progress);
                runIncrementalScan(sourcePath, excludePatterns, progress);
            }

            ctx.json(Map.of("success", true, "message", "All excluded scopes cleared and analysis updated"));
        } catch (Exception e) {
            log.error("Failed to clear excluded scopes: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    private static class FilteredBatch {
        final List<CodePackage> pkgs;
        final List<CodeType> types;
        final List<CodeField> fields;
        final List<CodeMethod> methods;
        final List<CodeRelationship> rels;
        final List<FileMeta> fileMetas;

        FilteredBatch(List<CodePackage> pkgs, List<CodeType> types, List<CodeField> fields,
                      List<CodeMethod> methods, List<CodeRelationship> rels, List<FileMeta> fileMetas) {
            this.pkgs = pkgs;
            this.types = types;
            this.fields = fields;
            this.methods = methods;
            this.rels = rels;
            this.fileMetas = fileMetas;
        }
    }

    private FilteredBatch filterExcludedScopeBatch(List<CodePackage> pkgs, List<CodeType> types,
                                                  List<CodeField> fields, List<CodeMethod> methods,
                                                  List<CodeRelationship> rels, List<FileMeta> fileMetas,
                                                  Set<String> excludedTypeFqns, Set<String> excludedPkgFqns,
                                                  Set<String> excludedSourceFiles) {
        if (excludedTypeFqns.isEmpty() && excludedPkgFqns.isEmpty() && (excludedSourceFiles == null || excludedSourceFiles.isEmpty())) {
            return new FilteredBatch(pkgs, types, fields, methods, rels, fileMetas);
        }

        Set<String> droppedTypeFqns = new HashSet<>(excludedTypeFqns);

        List<CodeType> filteredTypes = new ArrayList<>();
        if (types != null) {
            for (CodeType t : types) {
                if (isExcludedEntity(t.getFqn(), t.getPackageFqn(), excludedTypeFqns, excludedPkgFqns)) {
                    droppedTypeFqns.add(t.getFqn());
                } else {
                    filteredTypes.add(t);
                }
            }
        }

        List<CodeField> filteredFields = new ArrayList<>();
        if (fields != null) {
            for (CodeField f : fields) {
                if (!droppedTypeFqns.contains(f.getDeclaringTypeFqn()) &&
                    !isExcludedEntity(f.getDeclaringTypeFqn(), null, excludedTypeFqns, excludedPkgFqns)) {
                    filteredFields.add(f);
                }
            }
        }

        List<CodeMethod> filteredMethods = new ArrayList<>();
        if (methods != null) {
            for (CodeMethod m : methods) {
                if (!droppedTypeFqns.contains(m.getDeclaringTypeFqn()) &&
                    !isExcludedEntity(m.getDeclaringTypeFqn(), null, excludedTypeFqns, excludedPkgFqns)) {
                    filteredMethods.add(m);
                }
            }
        }

        List<CodePackage> filteredPkgs = new ArrayList<>();
        if (pkgs != null) {
            for (CodePackage p : pkgs) {
                if (!isExcludedPkg(p.getFqn(), excludedPkgFqns)) {
                    filteredPkgs.add(p);
                }
            }
        }

        List<CodeRelationship> filteredRels = new ArrayList<>();
        if (rels != null) {
            for (CodeRelationship r : rels) {
                String from = r.getFromEntityFqn();
                String to = r.getToEntityFqn();
                if (!isEntityFqnDropped(from, droppedTypeFqns, excludedPkgFqns) &&
                    !isEntityFqnDropped(to, droppedTypeFqns, excludedPkgFqns)) {
                    filteredRels.add(r);
                }
            }
        }

        List<FileMeta> filteredFileMetas = new ArrayList<>();
        if (fileMetas != null) {
            Map<String, Integer> totalTypesPerFile = new HashMap<>();
            Map<String, Integer> keptTypesPerFile = new HashMap<>();
            if (types != null) {
                for (CodeType t : types) {
                    if (t.getSourceFile() != null) {
                        totalTypesPerFile.merge(t.getSourceFile(), 1, Integer::sum);
                    }
                }
            }
            for (CodeType t : filteredTypes) {
                if (t.getSourceFile() != null) {
                    keptTypesPerFile.merge(t.getSourceFile(), 1, Integer::sum);
                }
            }

            for (FileMeta fm : fileMetas) {
                if (fm == null || fm.getFilePath() == null) continue;
                String path = fm.getFilePath();
                if (excludedSourceFiles != null && excludedSourceFiles.contains(path)) {
                    continue;
                }
                String norm = path.replace('\\', '/');
                boolean isExcludedPkg = false;
                for (String pkg : excludedPkgFqns) {
                    String pkgSlash = "/" + pkg.replace('.', '/') + "/";
                    if (norm.contains(pkgSlash) || norm.endsWith("/" + pkg.replace('.', '/') + ".java")) {
                        isExcludedPkg = true;
                        break;
                    }
                }
                if (isExcludedPkg) {
                    continue;
                }
                if (totalTypesPerFile.containsKey(path) && keptTypesPerFile.getOrDefault(path, 0) == 0) {
                    continue;
                }
                filteredFileMetas.add(fm);
            }
        }

        return new FilteredBatch(filteredPkgs, filteredTypes, filteredFields, filteredMethods, filteredRels, filteredFileMetas);
    }

    private static boolean isExcludedEntity(String fqn, String pkgFqn, Set<String> excludedTypeFqns, Set<String> excludedPkgFqns) {
        if (fqn != null && excludedTypeFqns.contains(fqn)) return true;
        if (pkgFqn != null && isExcludedPkg(pkgFqn, excludedPkgFqns)) return true;
        if (fqn != null) {
            for (String pkg : excludedPkgFqns) {
                if (fqn.startsWith(pkg + ".")) return true;
            }
        }
        return false;
    }

    private static boolean isExcludedPkg(String pkgFqn, Set<String> excludedPkgFqns) {
        if (pkgFqn == null) return false;
        for (String pkg : excludedPkgFqns) {
            if (pkgFqn.equals(pkg) || pkgFqn.startsWith(pkg + ".")) return true;
        }
        return false;
    }

    private static boolean isEntityFqnDropped(String fqn, Set<String> droppedTypeFqns, Set<String> excludedPkgFqns) {
        if (fqn == null) return false;
        if (droppedTypeFqns.contains(fqn)) return true;
        for (String typeFqn : droppedTypeFqns) {
            if (fqn.startsWith(typeFqn + "#") || fqn.startsWith(typeFqn + ".")) return true;
        }
        for (String pkg : excludedPkgFqns) {
            if (fqn.startsWith(pkg + ".")) return true;
        }
        return false;
    }
}


