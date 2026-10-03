package com.codelens.api;

import com.codelens.analysis.*;
import com.codelens.core.ExcludedScope;
import com.codelens.core.model.*;
import com.codelens.git.GitBlameService;
import com.codelens.git.GitRepoLocator;
import com.codelens.parser.AstVisitor;
import com.codelens.parser.JavaSourceScanner;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
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
import java.io.BufferedWriter;
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
    private final InconsistencyDetector inconsistencyDetector;
    private final ReportService      reportService;
    private final CriticalPathAnalyzer criticalPathAnalyzer;
    private final GitBlameService    gitBlameService;
    private final StressTestService  stressTestService = new StressTestService();
    private final JvmManagerService  jvmManager = new JvmManagerService();
    private final HeapAutoRecoveryManager heapWatchdog = new HeapAutoRecoveryManager();
    private final BackgroundTaskOrchestrator orchestrator = new BackgroundTaskOrchestrator();
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

    // ── Graph Layout Cache & Disk Persistence (SoftReference backed for automatic JVM GC cooperative eviction) ──
    private final Map<String, java.lang.ref.SoftReference<CallGraphAnalyzer.GraphView>> layoutCache = new ConcurrentHashMap<>();
    private final ModuleDependencyAnalyzer moduleDependencyAnalyzer;
    private volatile ModuleDependencyAnalyzer.FullModuleDependencyResult precomputedModuleResult = null;
    private final Map<String, ModuleDependencyAnalyzer.ModuleDependencyInsights> precomputedModuleInsights = new ConcurrentHashMap<>();
    private final Object modulePrecomputeLock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean modulePrecomputeRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final AtomicLong scanRevision = new AtomicLong(System.currentTimeMillis());
    private final java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
    private final ApiTracker apiTracker = new ApiTracker();

    // ── Precomputed Reports Cache & Disk Persistence ────────────────────────
    private final Map<String, Object> cachedReportsJson = new ConcurrentHashMap<>();
    private final Set<String> cachedReportArtifacts = ConcurrentHashMap.newKeySet();
    private final Object reportsPrecomputeLock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean reportsPrecomputeRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicReference<String> reportsPrecomputePhase = new java.util.concurrent.atomic.AtomicReference<>("Idle");
    private final java.util.concurrent.atomic.AtomicInteger reportsPrecomputePercentage = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicLong reportsLastGeneratedTimestamp = new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicLong reportsLastGenerationDurationMs = new java.util.concurrent.atomic.AtomicLong(0L);

    // ── Transient Entity Snapshot for Pipeline Inter-Stage Re-use ───────────
    private static class ScanEntitySnapshot {
        final List<CodePackage> packages;
        final List<CodeType> types;
        final List<CodeMethod> methods;
        final List<CodeField> fields;
        final List<CodeRelationship> relationships;
        final List<GitMeta> gitMetas;

        ScanEntitySnapshot(List<CodePackage> packages, List<CodeType> types, List<CodeMethod> methods,
                           List<CodeField> fields, List<CodeRelationship> relationships, List<GitMeta> gitMetas) {
            this.packages = packages != null ? packages : Collections.emptyList();
            this.types = types != null ? types : Collections.emptyList();
            this.methods = methods != null ? methods : Collections.emptyList();
            this.fields = fields != null ? fields : Collections.emptyList();
            this.relationships = relationships != null ? relationships : Collections.emptyList();
            this.gitMetas = gitMetas != null ? gitMetas : Collections.emptyList();
        }
    }

    private volatile ScanEntitySnapshot transientScanSnapshot = null;

    public void clearTransientScanSnapshot() {
        this.transientScanSnapshot = null;
    }

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

        // 1. Check in-memory cache (SoftReference)
        CallGraphAnalyzer.GraphView cached = null;
        java.lang.ref.SoftReference<CallGraphAnalyzer.GraphView> ref = layoutCache.get(cacheKey);
        if (ref != null) {
            cached = ref.get();
            if (cached == null) {
                layoutCache.remove(cacheKey); // evicted by JVM GC under heap pressure
            }
        }
        if (cached != null) {
            return cached;
        }

        // 2. Check disk cache
        File cacheFile = new File(getGraphCacheDir(), sanitizeCacheKey(cacheKey) + ".json");
        if (cacheFile.exists() && cacheFile.length() > 2) {
            try {
                CallGraphAnalyzer.GraphView diskView = jsonMapper.readValue(cacheFile, CallGraphAnalyzer.GraphView.class);
                if (diskView != null && diskView.nodes != null) {
                    layoutCache.put(cacheKey, new java.lang.ref.SoftReference<>(diskView));
                    return diskView;
                }
            } catch (Exception e) {
                log.warn("Failed to read graph layout cache from {}: {}", cacheFile.getName(), e.getMessage());
            }
        }

        // 3. Compute layout
        CallGraphAnalyzer.GraphView computed = computer.get();
        if (computed != null && computed.nodes != null) {
            layoutCache.put(cacheKey, new java.lang.ref.SoftReference<>(computed));
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
        precomputedModuleResult = null;
        precomputedModuleInsights.clear();
        cachedReportsJson.clear();
        cachedReportArtifacts.clear();
        clearTransientScanSnapshot();
        scanRevision.incrementAndGet();
        try {
            File dir = getGraphCacheDir();
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            File repDir = getReportsCacheDir();
            File[] repFiles = repDir.listFiles();
            if (repFiles != null) {
                for (File f : repFiles) {
                    f.delete();
                }
            }
            log.info("Cleared in-memory and disk graph layout and reports cache (rev={})", scanRevision.get());
        } catch (Exception e) {
            log.warn("Error invalidating graph and reports cache: {}", e.getMessage());
        }
    }

    public void warmupGraphCache(ScanProgress progress) {
        layoutWarmupRunning.set(true);
        layoutWarmupPhase.set("Precomputing Layouts");
        layoutWarmupPercentage.set(0);
        logProcessBanner("LAYOUT_WARMUP_STARTED", "Sunflower Layout Precomputer", resolveCurrentSourcePath(), "Precomputing graph layouts and module overview");
        try {
            if (progress != null) {
                progress.recordStageStart("LAYOUT", "Graph Layout & Topology Precomputation", "Precomputing sunflower spiral cluster layouts");
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
            boolean isHugeCodebase = methodCount > 8_000;

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
                    progress.setPercentage(82 + (int) ((i / (float) total) * 6.0));
                }
                try {
                    getOrComputeLayout(task.key, task.supplier);
                } catch (Exception e) {
                    log.warn("Skipping failed layout precomputation for '{}' ({}): {}", task.name, task.key, e.getMessage());
                }
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
                progress.setPercentage(88);
                Map<String, String> layoutMetrics = new LinkedHashMap<>();
                layoutMetrics.put("Layouts Cached", String.valueOf(total));
                layoutMetrics.put("Active Layout", "Sunflower Clustered (Full)");
                layoutMetrics.put("Placed Nodes", "Ready");
                layoutMetrics.put("Status", "Complete");
                progress.recordStageEnd("LAYOUT", "COMPLETE", String.format("Precomputed %d topology layouts", total), layoutMetrics);
            }

            // Standalone fallback: only trigger module and report background precomputation if not running under a scan
            if (progress == null && precomputedModuleResult == null && !cancelRequested) {
                try {
                    precomputeModuleDependencies(null);
                } catch (Exception e) {
                    log.warn("Module dependency precomputation deferred: {}", e.getMessage());
                }
            }

            if (progress == null && !cancelRequested) {
                try {
                    precomputeAllReports(null);
                } catch (Exception e) {
                    log.warn("Report precomputation deferred: {}", e.getMessage());
                }
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
            clearTransientScanSnapshot();
        }
    }

    public void warmupGraphCache() {
        warmupGraphCache(null);
    }

    public ModuleDependencyAnalyzer.FullModuleDependencyResult precomputeModuleDependencies(ScanProgress progress) {
        if (precomputedModuleResult != null && !precomputedModuleInsights.isEmpty()) {
            if (progress != null) {
                int modCount = (precomputedModuleResult.overview != null && precomputedModuleResult.overview.modules != null) ? precomputedModuleResult.overview.modules.size() : precomputedModuleInsights.size();
                progress.setModulesFound(modCount);
                progress.setPercentage(93);
                progress.setCurrentDetail(String.format("Precomputed %,d modules ready in memory", modCount));
            }
            return precomputedModuleResult;
        }

        // 1. Try disk cache first
        File diskCache = new File(getGraphCacheDir(), "module-dependencies.json");
        if (diskCache.exists() && diskCache.length() > 2) {
            try {
                ModuleDependencyAnalyzer.FullModuleDependencyResult diskResult =
                    jsonMapper.readValue(diskCache, ModuleDependencyAnalyzer.FullModuleDependencyResult.class);
                if (diskResult != null && diskResult.overview != null) {
                    populateModuleDependencyCaches(diskResult);
                    int modCount = (diskResult.overview.modules != null) ? diskResult.overview.modules.size() : precomputedModuleInsights.size();
                    if (progress != null) {
                        progress.setModulesFound(modCount);
                        progress.setPercentage(93);
                        progress.setCurrentDetail(String.format("Loaded %,d modules from disk cache", modCount));
                    }
                    log.info("Loaded precomputed module dependencies from disk cache: {} modules",
                        precomputedModuleInsights.size());
                    return diskResult;
                }
            } catch (Exception e) {
                log.warn("Failed reading module dependency disk cache: {}", e.getMessage());
            }
        }

        synchronized (modulePrecomputeLock) {
            if (precomputedModuleResult != null && !precomputedModuleInsights.isEmpty()) {
                if (progress != null) {
                    int modCount = (precomputedModuleResult.overview != null && precomputedModuleResult.overview.modules != null) ? precomputedModuleResult.overview.modules.size() : precomputedModuleInsights.size();
                    progress.setModulesFound(modCount);
                    progress.setPercentage(93);
                    progress.setCurrentDetail(String.format("Precomputed %,d modules ready in memory", modCount));
                }
                return precomputedModuleResult;
            }

            modulePrecomputeRunning.set(true);
            long start = System.currentTimeMillis();
            try {
                log.info("Auto-triggering background precomputation of module dependencies...");
                boolean isHuge = (callGraph != null && callGraph.vertexCount() > 25_000);
                List<CodePackage> packages;
                List<CodeType> types;
                List<CodeMethod> methods;
                List<CodeField> fields;
                List<CodeRelationship> relationships;

                ScanEntitySnapshot snapshot = this.transientScanSnapshot;
                if (snapshot != null && snapshot.types != null && !snapshot.types.isEmpty()) {
                    packages = (snapshot.packages != null && !snapshot.packages.isEmpty()) ? snapshot.packages : dao.findAllPackages();
                    types = snapshot.types;
                    methods = isHuge ? Collections.emptyList() : ((snapshot.methods != null && !snapshot.methods.isEmpty()) ? snapshot.methods : dao.findAllMethods());
                    fields = isHuge ? Collections.emptyList() : ((snapshot.fields != null && !snapshot.fields.isEmpty()) ? snapshot.fields : dao.findAllFields());
                    relationships = (callGraph != null && callGraph.getCallGraph() != null)
                        ? (isHuge ? dao.findStructuralRelationships() : dao.findNonCallRelationships())
                        : ((snapshot.relationships != null && !snapshot.relationships.isEmpty()) ? snapshot.relationships : dao.findAllRelationships());
                    log.info("Module dependency analyzer reused transient entity snapshot ({} types, {} methods, {} fields)",
                        types.size(), methods.size(), fields.size());
                } else {
                    packages = dao.findAllPackages();
                    types = dao.findAllTypes();
                    methods = isHuge ? Collections.emptyList() : dao.findAllMethods();
                    fields = isHuge ? Collections.emptyList() : dao.findAllFields();
                    relationships = (callGraph != null && callGraph.getCallGraph() != null)
                        ? (isHuge ? dao.findStructuralRelationships() : dao.findNonCallRelationships())
                        : dao.findAllRelationships();
                    if (!isHuge && types.size() <= 100_000) {
                        this.transientScanSnapshot = new ScanEntitySnapshot(packages, types, methods, fields,
                            (relationships != null && !(callGraph != null && callGraph.getCallGraph() != null)) ? relationships : null, null);
                    }
                }

                if (progress != null) {
                    progress.setActiveStage("MODULES");
                    progress.setCurrentPhase("Module Dependencies");
                    progress.setMessage("Analyzing package architecture & module boundaries…");
                    progress.setPercentage(89);
                    progress.setCurrentDetail(String.format("Extracting %,d packages, %,d types & %,d relationships…", packages.size(), types.size(), relationships.size()));
                    progress.setSubProgress(1, 4, "Extracting entity couplings");
                    progress.setDynamicMetrics(
                        "Packages", String.format("%,d", packages.size()),
                        "Types", String.format("%,d", types.size()),
                        "Cross Links", "Analyzing…",
                        "Cycles", "Checking…"
                    );
                }

                if (progress != null) {
                    progress.setPercentage(91);
                    progress.setCurrentDetail("Analyzing module boundaries and inter-package couplings…");
                    progress.setSubProgress(2, 4, "Module topology analysis");
                }

                ModuleDependencyAnalyzer.FullModuleDependencyResult result =
                    moduleDependencyAnalyzer.analyzeAllModules(packages, types, methods, fields, relationships, callGraph);

                populateModuleDependencyCaches(result);

                int modCount = (result != null && result.overview != null && result.overview.modules != null) ? result.overview.modules.size() : 0;
                int interLinks = (result != null && result.overview != null) ? result.overview.totalInterModuleTouchPoints : 0;
                boolean hasHighEfferent = (result != null && result.overview != null && result.overview.modules != null && result.overview.modules.stream().anyMatch(m -> "High Efferent".equals(m.stabilityRating)));

                if (progress != null) {
                    progress.setPercentage(93);
                    progress.setModulesFound(modCount);
                    progress.setCurrentDetail(String.format("Identified %,d architectural modules across %,d packages", modCount, packages.size()));
                    progress.setSubProgress(4, 4, "Complete");
                    progress.setDynamicMetrics(
                        "Modules", String.format("%,d", modCount),
                        "Packages", String.format("%,d", packages.size()),
                        "Cross Links", String.format("%,d", interLinks),
                        "Stability", hasHighEfferent ? "Efferent Risk" : "Stable Core"
                    );
                }

                // Persist to disk cache
                try {
                    jsonMapper.writeValue(diskCache, result);
                    log.info("Persisted module dependency result to disk cache ({} bytes)", diskCache.length());
                } catch (Exception e) {
                    log.warn("Failed writing module dependency disk cache: {}", e.getMessage());
                }

                log.info("Finished background precomputation of module dependencies in {} ms ({} modules indexed)",
                    System.currentTimeMillis() - start, precomputedModuleInsights.size());
                return result;
            } catch (Exception e) {
                log.error("Failed precomputing module dependencies: {}", e.getMessage(), e);
                return null;
            } finally {
                modulePrecomputeRunning.set(false);
            }
        }
    }

    private void populateModuleDependencyCaches(ModuleDependencyAnalyzer.FullModuleDependencyResult result) {
        if (result == null) return;
        this.precomputedModuleResult = result;
        this.precomputedModuleInsights.clear();

        if (result.insightsByModule != null) {
            for (Map.Entry<String, ModuleDependencyAnalyzer.ModuleDependencyInsights> entry : result.insightsByModule.entrySet()) {
                precomputedModuleInsights.put(entry.getKey().toLowerCase(), entry.getValue());
                if (entry.getValue().moduleName != null) {
                    precomputedModuleInsights.put(entry.getValue().moduleName.toLowerCase(), entry.getValue());
                }
                if (entry.getValue().packageFqn != null) {
                    precomputedModuleInsights.put(entry.getValue().packageFqn.toLowerCase(), entry.getValue());
                }
            }
        }
        if (result.insightsByPackage != null) {
            for (Map.Entry<String, ModuleDependencyAnalyzer.ModuleDependencyInsights> entry : result.insightsByPackage.entrySet()) {
                precomputedModuleInsights.put(entry.getKey().toLowerCase(), entry.getValue());
            }
        }
    }

    public File getReportsCacheDir() {
        File dir = new File(getGraphCacheDir(), "reports");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public boolean loadReportsFromDiskCache() {
        File dir = getReportsCacheDir();
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return false;

        boolean loadedAny = false;
        for (File f : files) {
            String name = f.getName();
            try {
                if (name.endsWith(".json")) {
                    String reportKey = name.substring(0, name.length() - 5);
                    cachedReportArtifacts.add(reportKey + ":json");
                    try (InputStream in = Files.newInputStream(f.toPath())) {
                        Object parsed = jsonMapper.readValue(in, Object.class);
                        cachedReportsJson.put(reportKey, parsed);
                    } catch (Exception ignored) {}
                    loadedAny = true;
                } else if (name.endsWith(".html")) {
                    String reportKey = name.substring(0, name.length() - 5);
                    if (!"html-snapshot".equals(reportKey)) {
                        try {
                            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                            if (!content.contains("report-html-pagination")) {
                                f.delete();
                                continue;
                            }
                        } catch (Exception ignored) {}
                    }
                    cachedReportArtifacts.add(reportKey + ":html");
                    if ("html-snapshot".equals(reportKey)) {
                        cachedReportsJson.putIfAbsent("html-snapshot", Map.of("report", "html-snapshot", "name", "Standalone Offline HTML Snapshot", "status", "ready"));
                    }
                    loadedAny = true;
                } else if (name.endsWith(".md")) {
                    String reportKey = name.substring(0, name.length() - 3);
                    cachedReportArtifacts.add(reportKey + ":markdown");
                    cachedReportArtifacts.add(reportKey + ":md");
                    loadedAny = true;
                } else if (name.endsWith(".csv")) {
                    String reportKey = name.substring(0, name.length() - 4);
                    cachedReportArtifacts.add(reportKey + ":csv");
                    loadedAny = true;
                }
            } catch (Exception e) {
                log.debug("Error reading cached report {}: {}", name, e.getMessage());
            }
        }
        if (loadedAny) {
            reportsPrecomputePhase.set("Ready (Loaded from disk cache)");
            reportsPrecomputePercentage.set(100);
            log.info("Loaded {} precomputed report artifacts from disk cache ({})", cachedReportArtifacts.size(), dir.getAbsolutePath());
        }
        return loadedAny;
    }

    public void triggerReportsPrecomputeAsync(ScanProgress progress) {
        triggerReportsPrecomputeAsync(progress, false);
    }

    public void triggerReportsPrecomputeAsync(ScanProgress progress, boolean force) {
        if (reportsPrecomputeRunning.get() || orchestrator.isQueued("reports-generator")) return;
        orchestrator.submit("reports-generator", BackgroundTaskOrchestrator.Priority.NORMAL, () -> {
            try {
                precomputeAllReports(progress, force);
            } catch (Throwable t) {
                log.warn("Background report precomputation failed: {}", t.getMessage());
            }
        });
    }

    public void precomputeAllReports(ScanProgress progress) {
        precomputeAllReports(progress, false);
    }

    public void precomputeAllReports(ScanProgress progress, boolean force) {
        if (!force && !cachedReportsJson.isEmpty() && cachedReportsJson.size() >= 13) {
            if (progress != null) {
                progress.setActiveStage("REPORTS");
                progress.setReportsFound(cachedReportsJson.size());
                progress.setPercentage(99);
                progress.setCurrentDetail(String.format("Loaded %,d intelligence reports from memory cache", cachedReportsJson.size()));
            }
            return;
        }

        // 1. Try disk cache first if not forced
        if (!force && loadReportsFromDiskCache() && cachedReportsJson.size() >= 13) {
            if (progress != null) {
                progress.setActiveStage("REPORTS");
                progress.setReportsFound(cachedReportsJson.size());
                progress.setPercentage(99);
                progress.setCurrentDetail(String.format("Loaded %,d intelligence reports from disk cache", cachedReportsJson.size()));
            }
            return;
        }

        synchronized (reportsPrecomputeLock) {
            if (!force && !cachedReportsJson.isEmpty() && cachedReportsJson.size() >= 13) {
                if (progress != null) {
                    progress.setActiveStage("REPORTS");
                    progress.setReportsFound(cachedReportsJson.size());
                    progress.setPercentage(99);
                    progress.setCurrentDetail(String.format("Loaded %,d intelligence reports from memory cache", cachedReportsJson.size()));
                }
                return;
            }

            if (force) {
                cachedReportsJson.clear();
                cachedReportArtifacts.clear();
            }

            reportsPrecomputeRunning.set(true);
            reportsPrecomputePercentage.set(2);
            reportsPrecomputePhase.set("Reading entities snapshot from database");
            long startTotal = System.currentTimeMillis();
            logProcessBanner("REPORTS_PRECOMPUTE_STARTED", "Codebase Intelligence Reports Generator", resolveCurrentSourcePath(),
                "Starting sequential precomputation of all 13 architecture, risk, persistence and concurrency reports");

            try {
                if (progress != null) {
                    progress.setActiveStage("REPORTS");
                    progress.setCurrentPhase("Generating Reports [0/13]");
                    progress.setMessage("Reading entities snapshot from database for reports generator…");
                    progress.setCurrentDetail("Reading database entities for reports generator…");
                    progress.setPercentage(93);
                    progress.setSubProgress(0, 13, "Reading entities snapshot");
                    progress.setDynamicMetrics(
                        "Reports Ready", "0 / 13",
                        "Active Report", "Initializing…",
                        "Artifacts", "0",
                        "Snapshot", "Pending"
                    );
                }

                ScanEntitySnapshot snapshot = this.transientScanSnapshot;
                List<CodeType> resolvedTypes;
                List<CodeMethod> methods;
                List<CodeField> fields;
                List<CodeRelationship> rels;
                List<GitMeta> gitMetas;

                if (snapshot != null && snapshot.types != null && !snapshot.types.isEmpty()) {
                    resolvedTypes = snapshot.types;
                    if (resolvedTypes.isEmpty()) {
                        reportsPrecomputePhase.set("Idle (No scanned types)");
                        reportsPrecomputePercentage.set(0);
                        return;
                    }
                    reportsPrecomputePercentage.set(5);
                    reportsPrecomputePhase.set("Reusing entities snapshot from pipeline");
                    methods = (snapshot.methods != null && !snapshot.methods.isEmpty()) ? snapshot.methods : dao.findAllMethods();
                    fields = (snapshot.fields != null && !snapshot.fields.isEmpty()) ? snapshot.fields : dao.findAllFields();
                    rels = (snapshot.relationships != null && !snapshot.relationships.isEmpty()) ? snapshot.relationships : dao.findAllRelationships();
                    gitMetas = (snapshot.gitMetas != null && !snapshot.gitMetas.isEmpty()) ? snapshot.gitMetas : dao.findAllGitMeta();
                    log.info("Reports generator reused transient entity snapshot ({} types, {} methods, {} fields, {} rels)",
                        resolvedTypes.size(), methods.size(), fields.size(), rels.size());
                } else {
                    // Query DB entities snapshot once
                    List<CodeType> dbTypes = dao.findAllTypes();
                    if (dbTypes.isEmpty()) {
                        reportsPrecomputePhase.set("Idle (No scanned types)");
                        reportsPrecomputePercentage.set(0);
                        return;
                    }

                    try {
                        if (dao.countZeroLineTypes() > 0) {
                            backfillZeroLineCountsIfPresent();
                            dbTypes = dao.findAllTypes();
                        }
                    } catch (Exception e) {
                        log.warn("Line count check during reports precompute: {}", e.getMessage());
                    }

                    resolvedTypes = dbTypes;
                    reportsPrecomputePercentage.set(5);
                    reportsPrecomputePhase.set("Reading methods, fields, and relationships");
                    methods = dao.findAllMethods();
                    fields = dao.findAllFields();
                    rels = dao.findAllRelationships();
                    gitMetas = dao.findAllGitMeta();
                }

                final List<CodeType> types = resolvedTypes;

                final int TOTAL_REPORTS = 13;

                // ── Helper runner for individual sequential report execution ─────────
                class ReportTaskRunner {
                    void run(int index, String reportKey, String title, Runnable action) {
                        int pct = 5 + (int) (((double) index / TOTAL_REPORTS) * 92);
                        reportsPrecomputePercentage.set(pct);
                        String phaseText = String.format("[%d/%d] %s", index, TOTAL_REPORTS, title);
                        reportsPrecomputePhase.set(phaseText);
                        if (progress != null) {
                            int scanPct = 93 + (int) (((double) (index - 1) / TOTAL_REPORTS) * 6.0);
                            progress.setActiveStage("REPORTS");
                            progress.setPercentage(Math.min(99, scanPct));
                            progress.setCurrentPhase(String.format("Generating Reports [%d/%d]", index, TOTAL_REPORTS));
                            progress.setMessage(String.format("Precomputing %s (%d of %d)…", title, index, TOTAL_REPORTS));
                            progress.setCurrentDetail(String.format("[%d/%d] %s", index, TOTAL_REPORTS, title));
                            progress.setSubProgress(index, TOTAL_REPORTS, title);
                            progress.setDynamicMetrics(
                                "Reports Ready", String.format("%d / %d", index - 1, TOTAL_REPORTS),
                                "Active Report", title,
                                "Artifacts", String.valueOf(cachedReportArtifacts.size()),
                                "Snapshot", index == 13 ? "Compiling" : (cachedReportArtifacts.contains("html-snapshot:html") ? "Ready" : "Pending")
                            );
                        }

                        logProcessBanner("REPORT_BUILD_STARTED", phaseText, reportKey, "Computing analysis model and rendering artifacts");
                        log.info("[REPORT {}/{}] Starting precomputation: {} ({})", index, TOTAL_REPORTS, title, reportKey);
                        long repStart = System.currentTimeMillis();
                        try {
                            action.run();
                            long repDuration = System.currentTimeMillis() - repStart;
                            logProcessBanner("REPORT_BUILD_COMPLETED", phaseText, reportKey,
                                String.format("Successfully precomputed & cached in %d ms", repDuration));
                            log.info("[REPORT {}/{}] COMPLETED {} in {} ms", index, TOTAL_REPORTS, title, repDuration);
                            if (progress != null) {
                                int afterScanPct = 93 + (int) (((double) index / TOTAL_REPORTS) * 6.0);
                                progress.setPercentage(Math.min(99, afterScanPct));
                                progress.setDynamicMetrics(
                                    "Reports Ready", String.format("%d / %d", index, TOTAL_REPORTS),
                                    "Active Report", title,
                                    "Artifacts", String.valueOf(cachedReportArtifacts.size()),
                                    "Snapshot", index == 13 ? "Ready" : (cachedReportArtifacts.contains("html-snapshot:html") ? "Ready" : "Pending")
                                );
                            }
                        } catch (Throwable t) {
                            long repDuration = System.currentTimeMillis() - repStart;
                            log.error("[REPORT {}/{}] FAILED {} after {} ms: {}", index, TOTAL_REPORTS, title, repDuration, t.getMessage(), t);
                            logProcessBanner("REPORT_BUILD_FAILED", phaseText, reportKey, "Error: " + t.getMessage());
                        } finally {
                            // Proactive memory check and trim between intensive analytical reports
                            Runtime rt = Runtime.getRuntime();
                            long usedBytes = rt.totalMemory() - rt.freeMemory();
                            long maxBytes = rt.maxMemory();
                            if ((double) usedBytes / maxBytes > 0.70) {
                                log.info("[REPORT {}/{}] Heap pressure at {:.1f}% ({}MB/{}MB). Running proactive GC trim...",
                                    index, TOTAL_REPORTS, ((double) usedBytes / maxBytes) * 100.0, usedBytes / (1024 * 1024), maxBytes / (1024 * 1024));
                                System.gc();
                            }
                        }
                    }
                }

                ReportTaskRunner runner = new ReportTaskRunner();

                final java.util.concurrent.atomic.AtomicReference<ReportService.ArchitectureReportData> refArch = new java.util.concurrent.atomic.AtomicReference<>();
                final java.util.concurrent.atomic.AtomicReference<ReportService.ChangeRiskReportData> refRisk = new java.util.concurrent.atomic.AtomicReference<>();
                final java.util.concurrent.atomic.AtomicReference<ReportService.DeadCodeReportData> refDead = new java.util.concurrent.atomic.AtomicReference<>();
                final java.util.concurrent.atomic.AtomicReference<ReportService.CircularDependencyReportData> refCycles = new java.util.concurrent.atomic.AtomicReference<>();
                final java.util.concurrent.atomic.AtomicReference<ReportService.ArchetypeGovernanceReportData> refGov = new java.util.concurrent.atomic.AtomicReference<>();
                final java.util.concurrent.atomic.AtomicReference<ReportService.TechnicalDebtReportData> refDebt = new java.util.concurrent.atomic.AtomicReference<>();

                // 1. Architecture Report
                runner.run(1, "architecture", "Architecture & Coupling Report", () -> {
                    ReportService.ArchitectureReportData data = reportService.buildArchitectureData(types, methods, fields, rels);
                    refArch.set(data);
                    cacheReport("architecture", data,
                        reportService.renderArchitectureHtml(data),
                        reportService.renderArchitectureMarkdown(data),
                        null);
                });

                // 2. Change Risk & Blast Radius Matrix
                runner.run(2, "change-risk", "Change Risk & Blast Radius Matrix", () -> {
                    ReportService.ChangeRiskReportData data = reportService.buildChangeRiskData(types, methods, fields, rels, gitMetas);
                    refRisk.set(data);
                    cacheReport("change-risk", data,
                        reportService.renderChangeRiskHtml(data),
                        reportService.renderChangeRiskMarkdown(data),
                        reportService.renderChangeRiskCsv(data));
                });

                // 3. Dead Code & Orphaned Entry Points
                runner.run(3, "dead-code", "Dead Code & Reachability Analysis", () -> {
                    ReportService.DeadCodeReportData data = reportService.buildDeadCodeData(types, methods, fields, rels);
                    refDead.set(data);
                    cacheReport("dead-code", data,
                        reportService.renderDeadCodeHtml(data),
                        reportService.renderDeadCodeMarkdown(data),
                        reportService.renderDeadCodeCsv(data));
                });

                // 4. Circular Dependencies & Tangling
                runner.run(4, "circular-dependencies", "Circular Dependencies & Tangling", () -> {
                    ReportService.CircularDependencyReportData data = reportService.buildCircularDependencyData(types, methods, rels);
                    refCycles.set(data);
                    cacheReport("circular-dependencies", data,
                        reportService.renderCircularDependencyHtml(data),
                        reportService.renderCircularDependencyMarkdown(data),
                        reportService.renderCircularDependencyCsv(data));
                });

                // 5. Archetype Governance & Compliance
                runner.run(5, "archetype-governance", "Enterprise Archetype Governance", () -> {
                    ReportService.ArchetypeGovernanceReportData data = reportService.buildArchetypeGovernanceData(types, methods, fields, rels);
                    refGov.set(data);
                    cacheReport("archetype-governance", data,
                        reportService.renderArchetypeGovernanceHtml(data),
                        reportService.renderArchetypeGovernanceMarkdown(data),
                        reportService.renderArchetypeGovernanceCsv(data));
                });

                // 6. Technical Debt & SQALE Remediation ROI
                runner.run(6, "technical-debt", "Technical Debt & SQALE Remediation ROI", () -> {
                    ReportService.TechnicalDebtReportData data = reportService.buildTechnicalDebtData(types, methods, fields, rels);
                    refDebt.set(data);
                    cacheReport("technical-debt", data,
                        reportService.renderTechnicalDebtHtml(data),
                        reportService.renderTechnicalDebtMarkdown(data),
                        reportService.renderTechnicalDebtCsv(data));
                });

                // 7. Executive Architectural Health Scorecard
                runner.run(7, "executive-summary", "Executive Architectural Health Scorecard", () -> {
                    ReportService.ExecutiveSummaryReportData data = reportService.buildExecutiveSummaryData(
                        types, methods, fields, rels, gitMetas,
                        refArch.get(), refRisk.get(), refCycles.get(), refGov.get(), refDead.get(), refDebt.get());
                    cacheReport("executive-summary", data,
                        reportService.renderExecutiveSummaryHtml(data),
                        reportService.renderExecutiveSummaryMarkdown(data),
                        reportService.renderExecutiveSummaryCsv(data));

                    refRisk.set(null);
                    refDead.set(null);
                    refCycles.set(null);
                    refGov.set(null);
                    refDebt.set(null);
                });

                // 8. Code Quality & Security Audit
                runner.run(8, "review", "Code Quality & Security Audit", () -> {
                    ReportService.ReviewReportData data = reportService.buildReviewReportData(types);
                    cacheReport("review", data,
                        reportService.renderReviewHtml(data),
                        reportService.renderReviewMarkdown(data),
                        reportService.renderReviewCsv(data));
                });

                // 9. Codebase Inventory & Metrics Census
                runner.run(9, "metrics", "Codebase Inventory & Metrics Census", () -> {
                    ReportService.MetricsReportData data = reportService.buildMetricsData(types, methods, fields);
                    cacheReport("metrics", data,
                        reportService.renderMetricsHtml(data),
                        reportService.renderMetricsMarkdown(data),
                        reportService.renderMetricsCsv(data));
                });

                // 10. API Surface & REST Endpoint Catalog
                runner.run(10, "api-catalog", "API Surface & REST Endpoint Catalog", () -> {
                    ReportService.ApiCatalogReportData data = reportService.buildApiCatalogData(types, methods, rels);
                    cacheReport("api-catalog", data,
                        reportService.renderApiCatalogHtml(data),
                        reportService.renderApiCatalogMarkdown(data),
                        reportService.renderApiCatalogCsv(data));
                });

                // 11. Database & Data Access Flow
                runner.run(11, "database-access", "Database & Data Access Flow", () -> {
                    ReportService.DatabaseAccessReportData data = reportService.buildDatabaseAccessData(types, methods, fields, rels);
                    cacheReport("database-access", data,
                        reportService.renderDatabaseAccessHtml(data),
                        reportService.renderDatabaseAccessMarkdown(data),
                        reportService.renderDatabaseAccessCsv(data));
                });

                // 12. Concurrency & Thread Safety Audit
                runner.run(12, "concurrency-audit", "Concurrency & Thread Safety Audit", () -> {
                    ReportService.ConcurrencyAuditReportData data = reportService.buildConcurrencyAuditData(types, methods, fields, rels);
                    cacheReport("concurrency-audit", data,
                        reportService.renderConcurrencyAuditHtml(data),
                        reportService.renderConcurrencyAuditMarkdown(data),
                        reportService.renderConcurrencyAuditCsv(data));
                });

                // 13. Standalone Offline Graph Snapshot
                runner.run(13, "html-snapshot", "Standalone Offline HTML Snapshot", () -> {
                    Object fullGraph = callGraph.precomputedFullGraphView(false);
                    Object archGraph = callGraph.precomputedArchitectureGraphView(null, null);
                    String projectName = (!types.isEmpty() && types.get(0).getPackageFqn() != null && !types.get(0).getPackageFqn().isBlank() ? types.get(0).getPackageFqn() : "Codebase");
                    ReportService.ArchitectureReportData archData = refArch.get();
                    if (archData == null) {
                        Object cachedArch = cachedReportsJson.get("architecture");
                        if (cachedArch instanceof ReportService.ArchitectureReportData ard) {
                            archData = ard;
                        } else if (cachedArch instanceof Map) {
                            try {
                                archData = jsonMapper.convertValue(cachedArch, ReportService.ArchitectureReportData.class);
                            } catch (Exception ignored) {}
                        }
                    }
                    if (archData == null) {
                        archData = reportService.buildArchitectureData(types, methods, fields, rels);
                    }

                    File snapshotFile = new File(getReportsCacheDir(), "html-snapshot.html");
                    boolean snapshotSuccess = false;
                    try (BufferedWriter writer = Files.newBufferedWriter(snapshotFile.toPath(), StandardCharsets.UTF_8)) {
                        reportService.writeInteractiveHtmlSnapshot(writer, projectName, fullGraph, archGraph, archData);
                        snapshotSuccess = (snapshotFile.exists() && snapshotFile.length() > 5000);
                    } catch (Exception e) {
                        log.error("Failed streaming interactive HTML snapshot: {}", e.getMessage(), e);
                    }

                    Map<String, Object> snapshotMeta = new LinkedHashMap<>();
                    snapshotMeta.put("report", "html-snapshot");
                    snapshotMeta.put("name", "Standalone Offline HTML Snapshot");
                    snapshotMeta.put("status", snapshotSuccess ? "ready" : "error");
                    snapshotMeta.put("file", "html-snapshot.html");
                    snapshotMeta.put("sizeBytes", snapshotFile.exists() ? snapshotFile.length() : 0);
                    snapshotMeta.put("generatedAt", System.currentTimeMillis());

                    cachedReportsJson.put("html-snapshot", snapshotMeta);
                    if (snapshotSuccess) {
                        cachedReportArtifacts.add("html-snapshot:html");
                        cachedReportArtifacts.add("html-snapshot:json");
                        writeJsonToFile(new File(getReportsCacheDir(), "html-snapshot.json"), snapshotMeta);
                    }

                    refArch.set(null);
                });

                long duration = System.currentTimeMillis() - startTotal;
                reportsLastGeneratedTimestamp.set(System.currentTimeMillis());
                reportsLastGenerationDurationMs.set(duration);
                reportsPrecomputePercentage.set(100);
                reportsPrecomputePhase.set(String.format("Ready (All %d reports precomputed in %dms)", TOTAL_REPORTS, duration));

                if (progress != null) {
                    progress.setPercentage(99);
                    progress.setReportsFound(TOTAL_REPORTS);
                    progress.setCurrentDetail(String.format("Precomputed all %d codebase intelligence reports & artifacts", TOTAL_REPORTS));
                    progress.setSubProgress(TOTAL_REPORTS, TOTAL_REPORTS, "All reports precomputed");
                    progress.setDynamicMetrics(
                        "Reports Ready", "13 / 13",
                        "Active Report", "All Reports Complete",
                        "Artifacts", String.valueOf(cachedReportArtifacts.size()),
                        "Snapshot", "Ready"
                    );
                }

                logProcessBanner("REPORTS_PRECOMPUTE_COMPLETED", "Codebase Intelligence Reports Generator", resolveCurrentSourcePath(),
                    String.format("All %d reports precomputed and cached in %d ms (%d artifacts)", TOTAL_REPORTS, duration, cachedReportArtifacts.size()));
                log.info("Finished precomputing all {} reports in {} ms ({} cache entries)", TOTAL_REPORTS, duration, cachedReportArtifacts.size());

            } catch (Exception e) {
                reportsPrecomputePhase.set("Error: " + e.getMessage());
                log.error("Failed precomputing reports: {}", e.getMessage(), e);
                logProcessBanner("REPORTS_PRECOMPUTE_FAILED", "Codebase Intelligence Reports Generator", resolveCurrentSourcePath(), "Error: " + e.getMessage());
            } finally {
                reportsPrecomputeRunning.set(false);
                clearTransientScanSnapshot();
            }
        }
    }

    private void cacheReport(String reportKey, Object jsonData, String html, String md, String csv) {
        if (jsonData != null) {
            cachedReportsJson.put(reportKey, jsonData);
            cachedReportArtifacts.add(reportKey + ":json");
            writeJsonToFile(new File(getReportsCacheDir(), reportKey + ".json"), jsonData);
        }
        if (html != null) {
            cachedReportArtifacts.add(reportKey + ":html");
            writeStringToFile(new File(getReportsCacheDir(), reportKey + ".html"), html);
        }
        if (md != null) {
            cachedReportArtifacts.add(reportKey + ":markdown");
            cachedReportArtifacts.add(reportKey + ":md");
            writeStringToFile(new File(getReportsCacheDir(), reportKey + ".md"), md);
        }
        if (csv != null) {
            cachedReportArtifacts.add(reportKey + ":csv");
            writeStringToFile(new File(getReportsCacheDir(), reportKey + ".csv"), csv);
        }
    }

    private void writeJsonToFile(File file, Object data) {
        if (file == null || data == null) return;
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            jsonMapper.writeValue(writer, data);
        } catch (Exception e) {
            log.debug("Failed writing JSON file {}: {}", file.getName(), e.getMessage());
        }
    }

    private void writeStringToFile(File file, String content) {
        if (file == null || content == null) return;
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Failed writing file {}: {}", file.getName(), e.getMessage());
        }
    }

    public synchronized void backfillZeroLineCountsIfPresent() {
        try {
            int zeroCount = dao.countZeroLineTypes();
            if (zeroCount == 0) return;
            log.info("Found {} types with zero line count; backfilling lines from source files...", zeroCount);
            List<CodeType> allTypes = dao.findAllTypes();
            Map<String, List<CodeType>> bySourceFile = new HashMap<>();
            for (CodeType t : allTypes) {
                if (t.getSourceFile() != null && !t.getSourceFile().isBlank()) {
                    bySourceFile.computeIfAbsent(t.getSourceFile(), k -> new ArrayList<>()).add(t);
                }
            }

            JavaParser parser = new JavaParser(JavaSourceScanner.createDefaultParserConfig());
            AstVisitor visitor = new AstVisitor();
            List<CodeType> typesToUpdate = new ArrayList<>();
            List<CodeMethod> methodsToUpdate = new ArrayList<>();

            for (Map.Entry<String, List<CodeType>> entry : bySourceFile.entrySet()) {
                Path path = Paths.get(entry.getKey());
                if (!Files.isRegularFile(path)) continue;

                try {
                    ParseResult<CompilationUnit> pr = parser.parse(path);
                    if (pr.isSuccessful() && pr.getResult().isPresent()) {
                        AstVisitor.VisitContext ctx = new AstVisitor.VisitContext();
                        ctx.sourceFile = path.toAbsolutePath().toString();
                        visitor.visit(pr.getResult().get(), ctx);

                        for (CodeType parsedType : ctx.types) {
                            if (parsedType.getLineCount() > 0) {
                                typesToUpdate.add(parsedType);
                            }
                        }
                        for (CodeMethod parsedMethod : ctx.methods) {
                            if (parsedMethod.getEndLine() > 0) {
                                methodsToUpdate.add(parsedMethod);
                            }
                        }
                    } else {
                        int lineCount = (int) Files.lines(path).count();
                        for (CodeType t : entry.getValue()) {
                            t.setLineCount(lineCount);
                            t.setStartLine(1);
                            t.setEndLine(lineCount);
                            typesToUpdate.add(t);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not parse file for line backfill {}: {}", path, e.getMessage());
                }
            }

            if (!typesToUpdate.isEmpty() || !methodsToUpdate.isEmpty()) {
                dao.updateTypeAndMethodRanges(typesToUpdate, methodsToUpdate);
                log.info("Successfully backfilled line numbers for {} types and {} methods.", typesToUpdate.size(), methodsToUpdate.size());
            }
        } catch (Exception e) {
            log.warn("Line count backfill encountered an error: {}", e.getMessage(), e);
        }
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
        this.inconsistencyDetector = new InconsistencyDetector();
        this.moduleDependencyAnalyzer = new ModuleDependencyAnalyzer();
        this.reportService         = new ReportService(this.callGraph, this.fieldImpact, this.codeReviewEngine);
        this.criticalPathAnalyzer  = new CriticalPathAnalyzer(this.callGraph);
        this.gitBlameService       = new GitBlameService();
        this.port                  = port;

        // ── Initialize Heap Auto-Recovery Watchdog & Hooks ─────────────────────
        this.heapWatchdog.registerRecoveryHook("In-Memory Graph & Module Layout Caches", () -> {
            int layouts = layoutCache.size();
            int modules = precomputedModuleInsights.size();
            layoutCache.clear();
            precomputedModuleInsights.clear();
            precomputedModuleResult = null;
            clearTransientScanSnapshot();
            log.info("Heap Auto-Recovery: evicted {} layout and {} module in-memory caches", layouts, modules);
        });
        this.heapWatchdog.registerRecoveryHook("In-Memory Reports JSON Models Cache", () -> {
            int reportsCount = cachedReportsJson.size();
            cachedReportsJson.clear();
            log.info("Heap Auto-Recovery: evicted {} in-memory report JSON models (disk cache retained)", reportsCount);
        });
        this.heapWatchdog.registerRecoveryHook("H2 Database Page Cache Shrink (16MB)", () -> {
            this.db.trimCache(16384);
        });
        this.heapWatchdog.setOnCircuitBreakerReset(() -> {
            this.db.restoreDefaultCache();
            this.orchestrator.dispatch();
        });
        this.orchestrator.setHeapAutoRecoveryManager(this.heapWatchdog);
        this.stressTestService.setHeapAutoRecoveryManager(this.heapWatchdog);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Start / Stop
    // ─────────────────────────────────────────────────────────────────────────

    public void start() {
        this.heapWatchdog.startWatchdog();

        // Install JVM-wide default uncaught exception handler for any thread encountering OOM or fatal crash
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Throwable root = throwable;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            if (root instanceof OutOfMemoryError) {
                log.error("UNCAUGHT OUTOFMEMORYERROR on thread [{}]: {}", thread.getName(), root.getMessage());
                heapWatchdog.handleTrappedOOM("Thread[" + thread.getName() + "]", root);
            } else {
                log.error("Uncaught exception on thread [{}]: {}", thread.getName(), throwable.getMessage(), throwable);
                com.codelens.storage.DiagnosticLogManager.recordCrashOrFailure(
                    "CRASH",
                    "UncaughtThreadException [" + thread.getName() + "]",
                    throwable,
                    Map.of(
                        "threadName", thread.getName(),
                        "threadId", thread.getId(),
                        "threadState", thread.getState().toString()
                    )
                );
            }
        });

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
            if (ctx.path().startsWith("/api")) {
                ctx.attribute("startTime", System.currentTimeMillis());
                apiTracker.recordRequestStart(ctx.method().name(), ctx.path());
            }
        });
        app.after(ctx -> {
            if (ctx.path().startsWith("/api")) {
                Long start = ctx.attribute("startTime");
                long duration = start != null ? (System.currentTimeMillis() - start) : 0;
                String matchedPath = null;
                try {
                    matchedPath = ctx.endpointHandlerPath();
                } catch (Throwable ignored) {}
                if (matchedPath == null || matchedPath.isBlank() || matchedPath.startsWith("No handler")) {
                    matchedPath = ctx.path();
                }
                apiTracker.recordRequestEnd(ctx.method().name(), matchedPath, ctx.status().getCode(), duration);
            }
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

        // ── Scale & Stress Testing ─────────────────────────────────────────────
        app.post("/api/stress-test/start",         this::startStressTest);
        app.get("/api/stress-test/status",         this::getStressTestStatus);
        app.post("/api/stress-test/stop",          this::stopStressTest);

        // ── JVM Management & Telemetry ────────────────────────────────────────
        app.get("/api/jvm/metrics",                this::getJvmMetrics);
        app.post("/api/jvm/gc",                    this::triggerJvmGc);
        app.get("/api/jvm/threads",                this::listJvmThreads);
        app.get("/api/jvm/threads/{id}/stack",     this::getJvmThreadStack);
        app.get("/api/jvm/thread-dump",            this::getJvmThreadDump);
        app.get("/api/jvm/deadlocks",              this::getJvmDeadlocks);
        app.post("/api/jvm/trim-memory",           this::trimJvmMemory);
        app.get("/api/jvm/auto-recovery",          this::getJvmAutoRecovery);
        app.post("/api/jvm/auto-recovery/trigger", this::triggerJvmAutoRecovery);
        app.post("/api/jvm/auto-recovery/simulate", this::simulateJvmAutoRecovery);
        app.post("/api/jvm/auto-recovery/reset-circuit-breaker", this::resetJvmCircuitBreaker);

        // ── Stats ─────────────────────────────────────────────────────────────
        app.get("/api/stats",        this::getStats);


        // ── Packages & Modules ────────────────────────────────────────────────
        app.get("/api/packages",                        this::listPackages);
        app.get("/api/packages/{fqn}/types",            this::typesByPackage);
        app.get("/api/packages/{fqn}/dependencies",     this::getPackageDependencies);
        app.get("/api/modules/dependencies",            this::getAllModuleDependencies);
        app.get("/api/modules/insights",                this::getAllModuleInsights);
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

        // ── Code Review & Inconsistency Detection ──────────────────────────────
        app.post("/api/review",              this::reviewCode);
        app.get("/api/inconsistencies",      this::getInconsistencies);

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
        app.get("/api/reports/all",                   this::getAllReports);
        app.get("/api/reports/architecture",          this::getArchitectureReport);
        app.get("/api/reports/change-risk",           this::getChangeRiskReport);
        app.get("/api/reports/dead-code",             this::getDeadCodeReport);
        app.get("/api/reports/circular-dependencies", this::getCircularDependenciesReport);
        app.get("/api/reports/archetype-governance",  this::getArchetypeGovernanceReport);
        app.get("/api/reports/technical-debt",        this::getTechnicalDebtReport);
        app.get("/api/reports/executive-summary",     this::getExecutiveSummaryReport);
        app.get("/api/reports/review",                this::getReviewReport);
        app.get("/api/reports/metrics",               this::getMetricsReport);
        app.get("/api/reports/api-catalog",           this::getApiCatalogReport);
        app.get("/api/reports/database-access",       this::getDatabaseAccessReport);
        app.get("/api/reports/concurrency-audit",      this::getConcurrencyAuditReport);
        app.get("/api/reports/html-snapshot",         this::getHtmlSnapshotReport);
        app.get("/api/reports/download",              this::downloadReport);
        app.post("/api/reports/regenerate",           this::regenerateReports);
        app.get("/api/reports/status",                this::getReportsStatus);

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


        // ── Diagnostic Flight Recorder & Incident Logs ────────────────────────
        app.get("/api/diagnostics/logs",            this::listDiagnosticLogs);
        app.get("/api/diagnostics/logs/{filename}", this::getDiagnosticLogContent);
        app.post("/api/diagnostics/capture",        this::captureDiagnosticSnapshot);
        app.delete("/api/diagnostics/logs",         this::clearDiagnosticLogs);

        // ── Global error handler & OOM Safety Net ────────────────────────────
        app.exception(Exception.class, (e, ctx) -> {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            if (root instanceof OutOfMemoryError) {
                HeapAutoRecoveryManager.AutoRecoveryIncident inc = heapWatchdog.handleTrappedOOM("HTTP: " + ctx.method() + " " + ctx.path(), root);
                logProcessBanner("OOM_RECOVERED", "Heap Space Sentinel", ctx.path(),
                    String.format("Auto-recovered: reclaimed %.1f MB · Heap: %d MB -> %d MB", inc.reclaimedMb, inc.heapBeforeMb, inc.heapAfterMb));
                ctx.status(503).json(Map.of(
                    "error", "Java heap space exhausted during request processing.",
                    "autoRecovered", true,
                    "reclaimedMb", inc.reclaimedMb,
                    "incident", inc.toMap(),
                    "diagnosticLogFile", inc.diagnosticLogFile != null ? inc.diagnosticLogFile : "",
                    "message", "CodeLens auto-recovery routine intercepted the OutOfMemoryError, purged volatile caches, and restored memory.",
                    "suggestion", "Query was too large for current heap. Narrow down packages or allocate more heap (-Xmx)."
                ));
                return;
            }
            log.error("Unhandled error on {} {}: {}", ctx.method(), ctx.path(), e.getMessage(), e);
            com.codelens.storage.DiagnosticLogManager.DiagnosticIncidentEntry diag =
                com.codelens.storage.DiagnosticLogManager.recordCrashOrFailure(
                    "FAILURE",
                    "HTTP " + ctx.method() + " " + ctx.path(),
                    e,
                    Map.of("queryString", ctx.queryString() != null ? ctx.queryString() : "", "ip", ctx.ip())
                );
            ctx.status(500).json(Map.of(
                "error", e.getMessage() != null ? e.getMessage() : e.toString(),
                "diagnosticLogFile", diag != null ? diag.fileName : ""
            ));
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
            } else {
                Map<String, Object> stats = dao.getStats();
                int typeCount = stats.containsKey("types") ? ((Number) stats.get("types")).intValue() : 0;
                if (typeCount > 0) {
                    ScanProgress synth = new ScanProgress(ScanProgress.Status.COMPLETE);
                    synth.setSourcePath(resolveCurrentSourcePath());
                    synth.setTypesFound(typeCount);
                    synth.setMethodsFound(stats.containsKey("methods") ? ((Number) stats.get("methods")).intValue() : 0);
                    synth.setFieldsFound(stats.containsKey("fields") ? ((Number) stats.get("fields")).intValue() : 0);
                    synth.setRelationshipsFound(stats.containsKey("relationships") ? ((Number) stats.get("relationships")).intValue() : 0);
                    synth.setTotalFiles(typeCount);
                    synth.setParsedFiles(typeCount);
                    synth.setProcessedFiles(typeCount);
                    synth.setCurrentPhase("Complete");
                    synth.setCurrentDetail("Ready");
                    synth.setMessage("Dataset loaded (" + typeCount + " types)");
                    synth.setStartTime(System.currentTimeMillis() - 1000);
                    synth.setEndTime(System.currentTimeMillis());
                    try { dao.saveScanMeta(synth); } catch (Exception ignored) {}
                    scanState.set(synth);
                    log.info("Synthesized scan metadata from populated database ({} types, {} methods)", typeCount, stats.get("methods"));
                }
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

        try {
            if (dao.countZeroLineTypes() > 0) {
                backfillZeroLineCountsIfPresent();
            }
        } catch (Exception e) {
            log.warn("Startup line count backfill error: {}", e.getMessage());
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
                        loadReportsFromDiskCache();
                        precomputeModuleDependencies(null);
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
        heapWatchdog.stopWatchdog();
        orchestrator.shutdown();
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

        final int innerWidth = 82; // Space between "| " and " |"
        String top = "+" + "=".repeat(innerWidth + 2) + "+";
        String bottom = "+" + "=".repeat(innerWidth + 2) + "+";

        String line1 = formatBannerField("CODELENS BACKGROUND PROCESS ", eventType, innerWidth, true);
        String line2 = formatBannerField("Process:   ", processName, innerWidth, true);
        String line3 = formatBannerField("Target:    ", path != null ? path : "-", innerWidth, false);
        String line4 = formatBannerField("Details:   ", details != null ? details : "-", innerWidth, true);

        String tsPart = "Timestamp: " + ts;
        String memPart = "Memory: " + usedMem + "MB / " + totalMem + "MB";
        int spacing = innerWidth - tsPart.length() - memPart.length();
        String line5;
        if (spacing > 0) {
            line5 = "| " + tsPart + " ".repeat(spacing) + memPart + " |";
        } else {
            line5 = "| " + truncateAndPad(tsPart + " " + memPart, innerWidth) + " |";
        }

        System.out.println("\n" +
            top + "\n" +
            line1 + "\n" +
            line2 + "\n" +
            line3 + "\n" +
            line4 + "\n" +
            line5 + "\n" +
            bottom);
        log.info("[PROCESS-{}] {} | Target: {} | Details: {}", eventType, processName, path, details);

        if (eventType != null && eventType.endsWith("_FAILED")) {
            try {
                com.codelens.storage.DiagnosticLogManager.recordCrashOrFailure(
                    "FAILURE",
                    processName + " [" + eventType + "]",
                    new RuntimeException(details != null ? details : eventType),
                    Map.of("targetPath", path != null ? path : "-", "eventType", eventType)
                );
            } catch (Exception ignored) {}
        }
    }

    private static String formatBannerField(String label, String value, int innerWidth, boolean truncateTail) {
        String val = (value != null && !value.isBlank()) ? value : "-";
        int maxValLen = innerWidth - label.length();
        if (val.length() > maxValLen) {
            if (truncateTail) {
                val = val.substring(0, Math.max(0, maxValLen - 3)) + "...";
            } else {
                val = "..." + val.substring(val.length() - Math.max(0, maxValLen - 3));
            }
        }
        String content = label + val;
        if (content.length() < innerWidth) {
            content = content + " ".repeat(innerWidth - content.length());
        }
        return "| " + content + " |";
    }

    private static String truncateAndPad(String text, int width) {
        if (text == null) text = "";
        if (text.length() > width) {
            return text.substring(0, width - 3) + "...";
        }
        return text + " ".repeat(width - text.length());
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
        layoutProc.put("currentDetail", String.format("%d graph layouts cached in memory (rev=%d)", cachedLayouts, scanRevision.get()));
        layoutProc.put("percentage", layoutPct);
        layoutProc.put("thread", isLayoutBuilding ? "codelens-layout-worker" : "-");
        layoutProc.put("canKill", isScanLayoutActive);
        layoutProc.put("canRestart", true);
        processes.add(layoutProc);

        // 5. Module Dependency & Touchpoint Engine
        Map<String, Object> moduleProc = new LinkedHashMap<>();
        moduleProc.put("id", "module-analyzer");
        moduleProc.put("name", "Module Dependency & Touchpoint Engine");
        moduleProc.put("type", "Cross-Module Coupling & Architectural Touchpoints");
        boolean isScanModuleActive = isScanning && sp != null && "MODULES".equals(sp.getActiveStage());
        boolean isModuleActive = modulePrecomputeRunning.get() || isScanModuleActive;
        boolean isModuleComplete = precomputedModuleResult != null;
        int cachedModules = precomputedModuleInsights.size();
        moduleProc.put("status", isModuleActive ? "RUNNING" : (isModuleComplete ? "COMPLETE" : "IDLE"));
        moduleProc.put("activeStage", isModuleActive ? "MODULE_DEPENDENCIES" : "IDLE");
        moduleProc.put("currentPhase", isScanModuleActive ? sp.getCurrentPhase() : (isModuleActive ? "Analyzing Module Dependencies & Touchpoints" : (isModuleComplete ? "Module Topology Ready" : "Idle")));
        String modDetail = isModuleComplete
            ? String.format("%d modules · %d package touchpoints analyzed", (precomputedModuleResult.overview != null && precomputedModuleResult.overview.modules != null) ? precomputedModuleResult.overview.modules.size() : cachedModules, cachedModules)
            : (isScanModuleActive ? sp.getCurrentDetail() : (isModuleActive ? "Analyzing cross-module dependencies and touchpoints..." : "Not computed yet"));
        moduleProc.put("currentDetail", modDetail);
        moduleProc.put("percentage", isScanModuleActive ? sp.getPercentage() : (isModuleActive ? 50 : (isModuleComplete ? 100 : 0)));
        moduleProc.put("thread", isScanModuleActive ? "codelens-scanner" : (isModuleActive ? "codelens-module-worker" : "-"));
        moduleProc.put("canKill", isScanModuleActive);
        moduleProc.put("canRestart", true);
        processes.add(moduleProc);

        // 6. Lucene Full-Text Search Indexer
        Map<String, Object> luceneProc = new LinkedHashMap<>();
        luceneProc.put("id", "lucene-indexer");
        luceneProc.put("name", "Lucene Full-Text Search Indexer");
        luceneProc.put("type", "Inverted Index & Tokenised Codebase Search");
        boolean isLuceneIndexing = isScanning && sp != null && "INDEX".equals(sp.getActiveStage());
        int luceneDocs = lucene.getDocumentCount();
        luceneProc.put("status", isLuceneIndexing ? "RUNNING" : (luceneDocs > 0 ? "COMPLETE" : "IDLE"));
        luceneProc.put("activeStage", isLuceneIndexing ? "INDEX" : "IDLE");
        luceneProc.put("currentPhase", isLuceneIndexing ? "Indexing Documents & Secondary Indexes" : (luceneDocs > 0 ? "Search Index Ready & Committed" : "Idle"));
        luceneProc.put("currentDetail", String.format("%,d searchable documents in Lucene inverted index", luceneDocs));
        luceneProc.put("percentage", isLuceneIndexing ? sp.getPercentage() : (luceneDocs > 0 ? 100 : 0));
        luceneProc.put("thread", isLuceneIndexing ? "codelens-scanner" : "-");
        luceneProc.put("canKill", false);
        luceneProc.put("canRestart", true);
        processes.add(luceneProc);

        // 7. Git History & Hotspots
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

        // 8. Database Connection Watchdog
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

        // 9. Scale & Stress Test Engine
        Map<String, Object> stressProc = new LinkedHashMap<>();
        stressProc.put("id", "stress-test");
        stressProc.put("name", "Scale & Stress Test Runner");
        stressProc.put("type", "High-Throughput Load & Capacity Benchmark");
        StressTestProgress stp = stressTestService.getProgress();
        boolean isStressRunning = stp != null && stp.getStatus() == StressTestProgress.Status.RUNNING;
        boolean isStressComplete = stp != null && stp.getStatus() == StressTestProgress.Status.COMPLETE;
        boolean isStressError = stp != null && stp.getStatus() == StressTestProgress.Status.ERROR;
        stressProc.put("status", isStressRunning ? "RUNNING" : (isStressComplete ? "COMPLETE" : (isStressError ? "ERROR" : "IDLE")));
        stressProc.put("activeStage", stp != null ? stp.getActiveStage() : "IDLE");
        stressProc.put("currentPhase", stp != null ? stp.getCurrentPhase() : "Idle");
        stressProc.put("currentDetail", stp != null ? (isStressError && stp.getErrorDetail() != null && !stp.getErrorDetail().isBlank() ? stp.getErrorDetail() : stp.getCurrentDetail()) : "");
        stressProc.put("percentage", stp != null ? stp.getPercentage() : 0);
        stressProc.put("durationMs", stp != null ? stp.getDurationMs() : 0);
        stressProc.put("startTime", stp != null ? stp.getStartTime() : 0);
        stressProc.put("thread", isStressRunning ? "codelens-stress-worker" : "-");
        stressProc.put("canKill", isStressRunning);
        stressProc.put("canRestart", true);
        processes.add(stressProc);

        // 10. Heap Memory Watchdog & Auto-Recovery
        Map<String, Object> autoRecMetrics = heapWatchdog.getStatusAndMetrics();
        Map<String, Object> heapProc = new LinkedHashMap<>();
        heapProc.put("id", "heap-watchdog");
        heapProc.put("name", "Heap Auto-Recovery Watchdog");
        heapProc.put("type", "Memory Sentinel & Circuit Breaker");
        boolean cbActive = (Boolean) autoRecMetrics.get("circuitBreakerActive");
        heapProc.put("status", cbActive ? "ALERT" : "ACTIVE");
        heapProc.put("activeStage", cbActive ? "CIRCUIT_BREAKER_OPEN" : "MONITORING");
        heapProc.put("currentPhase", cbActive ? "Circuit Breaker Active (Throttling)" : "Watching Heap Allocation (3s poll)");
        heapProc.put("currentDetail", String.format("%d recoveries · %.1f MB reclaimed · %d%% current heap",
            (Long) autoRecMetrics.get("totalRecoveries"),
            (Double) autoRecMetrics.get("totalReclaimedMb"),
            (Integer) autoRecMetrics.get("currentHeapPct")));
        heapProc.put("percentage", 100);
        heapProc.put("thread", "CodeLens-HeapWatchdog");
        heapProc.put("canKill", false);
        heapProc.put("canRestart", true);
        processes.add(heapProc);

        // 11. Codebase Intelligence Reports Generator
        Map<String, Object> reportsProc = new LinkedHashMap<>();
        reportsProc.put("id", "reports-generator");
        reportsProc.put("name", "Intelligence Reports Generator");
        reportsProc.put("type", "Deep Architecture, Risk & Metrics Precomputation");
        boolean isScanReportsActive = isScanning && sp != null && "REPORTS".equals(sp.getActiveStage());
        boolean isReportsRunning = reportsPrecomputeRunning.get() || isScanReportsActive;
        reportsProc.put("status", isReportsRunning ? "RUNNING" : (cachedReportsJson.size() > 0 ? "COMPLETE" : "IDLE"));
        reportsProc.put("activeStage", isReportsRunning ? "PRECOMPUTING" : (cachedReportsJson.size() > 0 ? "CACHED" : "IDLE"));
        reportsProc.put("currentPhase", isScanReportsActive ? sp.getCurrentPhase() : reportsPrecomputePhase.get());
        long lastGen = reportsLastGeneratedTimestamp.get();
        String genDetail = isScanReportsActive
            ? sp.getCurrentDetail()
            : (lastGen > 0
                ? String.format("%d reports cached · Last precomputed in %d ms (%s)",
                    cachedReportsJson.size(),
                    reportsLastGenerationDurationMs.get(),
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(lastGen)))
                : (isReportsRunning ? "Precomputing all 13 reports in background..." : "Not generated yet"));
        reportsProc.put("currentDetail", genDetail);
        reportsProc.put("percentage", isScanReportsActive ? sp.getPercentage() : reportsPrecomputePercentage.get());
        reportsProc.put("durationMs", reportsLastGenerationDurationMs.get());
        reportsProc.put("startTime", isReportsRunning ? (isScanReportsActive ? sp.getStartTime() : reportsLastGeneratedTimestamp.get()) : 0);
        reportsProc.put("thread", isScanReportsActive ? "codelens-scanner" : (isReportsRunning ? "codelens-reports-worker" : "-"));
        reportsProc.put("canKill", isScanReportsActive);
        reportsProc.put("canRestart", true);
        processes.add(reportsProc);

        // Enrich process entries with orchestrator queue, load weight, and dependency status
        for (Map<String, Object> proc : processes) {
            String id = (String) proc.get("id");
            BackgroundTaskOrchestrator.TaskSnapshot snap = orchestrator.getTaskSnapshot(id);
            if (snap != null) {
                proc.put("loadWeight", snap.loadUnits);
                proc.put("loadTier", snap.loadTier);
                proc.put("mutexGroup", snap.mutexGroup);
                if (snap.status == BackgroundTaskOrchestrator.TaskStatus.QUEUED) {
                    proc.put("status", "QUEUED");
                    proc.put("queueStatus", "QUEUED");
                    proc.put("queuePosition", snap.queuePosition);
                    proc.put("currentPhase", "Queued in background (position #" + snap.queuePosition + ")");
                } else if (snap.status == BackgroundTaskOrchestrator.TaskStatus.WAITING_DEPENDENCY) {
                    proc.put("status", "WAITING");
                    proc.put("queueStatus", "WAITING_DEPENDENCY");
                    proc.put("queuePosition", snap.queuePosition);
                    proc.put("waitingFor", snap.waitingFor);
                    String waitMsg = snap.waitingFor.isEmpty() ? "Prerequisites" : String.join(", ", snap.waitingFor);
                    proc.put("currentPhase", "Waiting on dependency: " + waitMsg);
                } else if (snap.status == BackgroundTaskOrchestrator.TaskStatus.THROTTLED) {
                    proc.put("status", "THROTTLED");
                    proc.put("queueStatus", "THROTTLED");
                    proc.put("queuePosition", snap.queuePosition);
                    proc.put("throttleReason", snap.throttleReason);
                    proc.put("currentPhase", snap.throttleReason != null ? snap.throttleReason : "Throttled (Memory High)");
                } else {
                    proc.put("queueStatus", snap.status.name());
                    proc.put("queuePosition", snap.queuePosition);
                }
            }
        }

        // System resources, JVM telemetry & Pool metrics
        Map<String, Object> jvmMetrics = jvmManager.getComprehensiveMetrics();
        long freeMem = Runtime.getRuntime().freeMemory();
        long totalMem = Runtime.getRuntime().totalMemory();
        long maxMem = Runtime.getRuntime().maxMemory();
        long usedMem = totalMem - freeMem;

        Map<String, Object> system = new LinkedHashMap<>(jvmMetrics);
        system.put("heapUsedMb", usedMem / (1024 * 1024));
        system.put("heapTotalMb", totalMem / (1024 * 1024));
        system.put("heapMaxMb", maxMem / (1024 * 1024));
        system.put("heapPercent", (int) ((usedMem * 100L) / maxMem));
        system.put("activeThreads", Thread.activeCount());
        system.put("dbPool", db.getPoolStats());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("processes", processes);
        resp.put("orchestrator", orchestrator.getOrchestratorSnapshot());
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

        if (("restart".equalsIgnoreCase(action) || "compact".equalsIgnoreCase(action) || "reindex".equalsIgnoreCase(action)) &&
            (orchestrator.isRunning("scanner") || orchestrator.isRunning("delta-scanner") || orchestrator.isRunning("stress-test"))) {
            Map<String, Object> errResp = new LinkedHashMap<>();
            errResp.put("success", false);
            errResp.put("action", action);
            errResp.put("message", "Cannot execute database maintenance '" + action + "' while a database scan or stress test is actively running. Stop the active process or wait for completion.");
            errResp.put("diagnostics", db.getDiagnostics());
            ctx.status(409).json(errResp);
            return;
        }

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
        orchestrator.cancel(id);
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
        } else if ("stress-test".equalsIgnoreCase(id)) {
            stressTestService.stopStressTest();
            ctx.json(Map.of("status", "killed", "processId", id, "message", "Scale & stress test cancellation requested"));
            return;
        }
        ctx.json(Map.of("status", "ok", "processId", id, "message", "Process signaled"));
    }

    private void restartProcess(Context ctx) {
        String id = ctx.pathParam("id");
        if ("stress-test".equalsIgnoreCase(id)) {
            StressTestProgress lastP = stressTestService.getProgress();
            int c = lastP != null && lastP.getTargetClasses() > 0 ? lastP.getTargetClasses() : 30_000;
            int f = lastP != null && lastP.getTargetFields() > 0 ? lastP.getTargetFields() : 150_000;
            long r = lastP != null && lastP.getTargetRelationships() > 0 ? lastP.getTargetRelationships() : 15_000_000L;
            String dir = lastP != null && lastP.getTargetDir() != null ? lastP.getTargetDir() : "/Volumes/Study/Projects/codelens/codelens-stress-data";

            if (stressTestService.isRunning()) {
                stressTestService.stopStressTest();
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }

            orchestrator.submit("stress-test", BackgroundTaskOrchestrator.Priority.HIGH, () -> {
                stressTestService.startStressTest(c, f, r, dir);
                while (stressTestService.isRunning()) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        stressTestService.stopStressTest();
                        break;
                    }
                }
            });
            ctx.json(Map.of("status", "restarted", "processId", id, "message", "Stress test queued in background orchestrator."));
            return;
        }

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
            orchestrator.submit("scanner", BackgroundTaskOrchestrator.Priority.HIGH, () -> runScan(currentPath, resolveCurrentExcludePatterns(), progress));
            ctx.json(Map.of("status", "restarted", "processId", id, "sourcePath", currentPath));
            return;
        } else if ("delta-scanner".equalsIgnoreCase(id)) {
            ScanProgress progress = new ScanProgress(ScanProgress.Status.SCANNING);
            progress.setSourcePath(currentPath);
            progress.setStartTime(System.currentTimeMillis());
            progress.setCurrentPhase("Delta Change Detection");
            progress.setMessage("Resuming & rescanning changed files…");
            scanState.set(progress);
            orchestrator.submit("delta-scanner", BackgroundTaskOrchestrator.Priority.HIGH, () -> runIncrementalScan(currentPath, resolveCurrentExcludePatterns(), progress));
            ctx.json(Map.of("status", "restarted", "processId", id, "sourcePath", currentPath));
            return;
        } else if ("call-graph".equalsIgnoreCase(id)) {
            orchestrator.submit("call-graph", BackgroundTaskOrchestrator.Priority.HIGH, () -> {
                graphWarmupRunning.set(true);
                graphWarmupPhase.set("Call Graph Analysis");
                graphWarmupPercentage.set(10);
                logProcessBanner("GRAPH_BUILD_STARTED", "Call Graph & Topology Engine", currentPath, "Manual rebuild of call graph & field impact requested");
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
                } catch (Throwable t) {
                    if (t instanceof OutOfMemoryError || (t.getCause() != null && t.getCause() instanceof OutOfMemoryError)) {
                        heapWatchdog.handleTrappedOOM("CallGraphRebuild", t);
                        graphWarmupPhase.set("Auto-Recovered from Heap Limit");
                        logProcessBanner("GRAPH_BUILD_OOM", "Call Graph Engine", currentPath, "OutOfMemoryError trapped; auto-recovery executed");
                    } else {
                        log.error("Failed to rebuild call graph", t);
                        graphWarmupPhase.set("Error: " + t.getMessage());
                        logProcessBanner("GRAPH_BUILD_FAILED", "Call Graph & Topology Engine", currentPath, "Error: " + t.getMessage());
                    }
                } finally {
                    graphWarmupRunning.set(false);
                }
            });
            ctx.json(Map.of("status", "restarted", "processId", id, "message", "Call graph rebuild queued in orchestrator"));
            return;
        } else if ("layout-engine".equalsIgnoreCase(id)) {
            orchestrator.submit("layout-engine", BackgroundTaskOrchestrator.Priority.HIGH, () -> {
                invalidateGraphCache();
                warmupGraphCache();
            });
            ctx.json(Map.of("status", "restarted", "processId", id, "message", "Layout precomputations queued in orchestrator"));
            return;
        } else if ("module-analyzer".equalsIgnoreCase(id)) {
            orchestrator.submit("module-analyzer", BackgroundTaskOrchestrator.Priority.HIGH, () -> precomputeModuleDependencies(null));
            ctx.json(Map.of("status", "restarted", "processId", id, "message", "Module dependency analysis queued in orchestrator"));
            return;
        } else if ("lucene-indexer".equalsIgnoreCase(id)) {
            orchestrator.submit("lucene-indexer", BackgroundTaskOrchestrator.Priority.HIGH, () -> {
                try {
                    logProcessBanner("LUCENE_REINDEX_STARTED", "Lucene Search Indexer", currentPath, "Manual full re-indexing of types, methods, fields");
                    List<CodeType> allTypes = dao.findAllTypes();
                    List<CodeMethod> allMethods = dao.findAllMethods();
                    List<CodeField> allFields = dao.findAllFields();
                    lucene.rebuildIndex(allTypes, allMethods, allFields);
                    logProcessBanner("LUCENE_REINDEX_COMPLETED", "Lucene Search Indexer", currentPath,
                        String.format("Indexed %,d documents", lucene.getDocumentCount()));
                } catch (Exception e) {
                    log.error("Failed to reindex Lucene: {}", e.getMessage(), e);
                    logProcessBanner("LUCENE_REINDEX_FAILED", "Lucene Search Indexer", currentPath, "Error: " + e.getMessage());
                }
            });
            ctx.json(Map.of("status", "restarted", "processId", id, "message", "Lucene full re-index queued in orchestrator"));
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
            orchestrator.submit("git-analyzer", BackgroundTaskOrchestrator.Priority.HIGH, () -> triggerGitAnalysis(validation.getRepoPath(), validation.getBranch()));
            ctx.json(Map.of("status", "restarted", "processId", id, "repoPath", validation.getRepoPath()));
            return;
        } else if ("db-watchdog".equalsIgnoreCase(id)) {
            Map<String, Object> sweepReport = db.sweepConnectionLeaks();
            String sweepMsg = String.valueOf(sweepReport.get("message"));
            logProcessBanner("DB_LEAK_SWEEP", "Database Connection Watchdog", "codelens_db", sweepMsg);
            ctx.json(Map.of("status", "restarted", "processId", id, "message", sweepMsg, "report", sweepReport));
            return;
        } else if ("heap-watchdog".equalsIgnoreCase(id)) {
            heapWatchdog.stopWatchdog();
            heapWatchdog.startWatchdog();
            HeapAutoRecoveryManager.AutoRecoveryIncident inc = heapWatchdog.triggerAutoRecovery("PROCESS_RESTART_REQUEST");
            orchestrator.dispatch();
            ctx.json(Map.of("status", "restarted", "processId", id, "message", "Heap Watchdog restarted & memory auto-recovered (" + inc.reclaimedMb + " MB freed)"));
            return;
        } else if ("reports-generator".equalsIgnoreCase(id)) {
            orchestrator.submit("reports-generator", BackgroundTaskOrchestrator.Priority.HIGH, () -> triggerReportsPrecomputeAsync(null, true));
            ctx.json(Map.of("status", "restarted", "processId", id, "message", "Reports regeneration queued in orchestrator"));
            return;
        }
        ctx.status(400).json(Map.of("error", "Unknown process id: " + id));
    }

    private void startStressTest(Context ctx) {
        Map<?, ?> body = Collections.emptyMap();
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception ignored) {}

        int classes = 30_000;
        int fields = 150_000;
        long rels = 15_000_000L;
        String dir = "/Volumes/Study/Projects/codelens/codelens-stress-data";

        if (body != null) {
            if (body.get("classes") instanceof Number n) classes = n.intValue();
            if (body.get("fields") instanceof Number n) fields = n.intValue();
            if (body.get("relationships") instanceof Number n) rels = n.longValue();
            if (body.get("targetDir") instanceof String s && !s.isBlank()) dir = s;
        }

        final int targetClasses = classes;
        final int targetFields = fields;
        final long targetRels = rels;
        final String targetDir = dir;

        if (stressTestService.isRunning() || orchestrator.isQueued("stress-test")) {
            ctx.status(409).json(Map.of("success", false, "message", "A stress test is already running or queued in orchestrator", "progress", stressTestService.getProgress()));
            return;
        }

        orchestrator.submit("stress-test", BackgroundTaskOrchestrator.Priority.HIGH, () -> {
            logProcessBanner("STRESS_TEST_STARTED", "Stress Test Runner", targetDir,
                String.format("Target: %,d classes, %,d fields, %,d relationships", targetClasses, targetFields, targetRels));
            stressTestService.startStressTest(targetClasses, targetFields, targetRels, targetDir);
            while (stressTestService.isRunning()) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    stressTestService.stopStressTest();
                    break;
                }
            }
        });

        BackgroundTaskOrchestrator.TaskSnapshot snap = orchestrator.getTaskSnapshot("stress-test");
        boolean isQueued = snap != null && (snap.status == BackgroundTaskOrchestrator.TaskStatus.QUEUED || snap.status == BackgroundTaskOrchestrator.TaskStatus.WAITING_DEPENDENCY);
        String msg = isQueued
            ? "Stress test submitted and queued (waiting for active database task or capacity slot)."
            : "Stress test started successfully.";

        Map<String, Object> respMap = new LinkedHashMap<>();
        respMap.put("success", true);
        respMap.put("message", msg);
        respMap.put("progress", stressTestService.getProgress());
        if (snap != null) respMap.put("orchestrator", snap.toMap());
        ctx.json(respMap);
    }

    private void getStressTestStatus(Context ctx) {
        ctx.json(stressTestService.getProgress());
    }

    private void stopStressTest(Context ctx) {
        stressTestService.stopStressTest();
        logProcessBanner("STRESS_TEST_STOPPED", "Stress Test Runner", "-", "User stopped stress test execution");
        ctx.json(Map.of("success", true, "message", "Stress test stop requested", "progress", stressTestService.getProgress()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JVM Management & Monitoring Handlers
    // ─────────────────────────────────────────────────────────────────────────
    private void getJvmMetrics(Context ctx) {
        ctx.json(jvmManager.getComprehensiveMetrics());
    }

    private void triggerJvmGc(Context ctx) {
        Map<String, Object> res = jvmManager.triggerGc();
        logProcessBanner("JVM_GC", "Garbage Collector", "System.gc()",
                String.format("Reclaimed %s MB (duration: %d ms)", res.get("freedMb"), res.get("durationMs")));
        ctx.json(res);
    }

    private void listJvmThreads(Context ctx) {
        String q = ctx.queryParam("q");
        String state = ctx.queryParam("state");
        List<Map<String, Object>> list = jvmManager.getThreadList(q, state);
        ctx.json(Map.of(
            "count", list.size(),
            "query", q != null ? q : "",
            "stateFilter", state != null ? state : "ALL",
            "threads", list
        ));
    }

    private void getJvmThreadStack(Context ctx) {
        String idParam = ctx.pathParam("id");
        try {
            long tid = Long.parseLong(idParam);
            Map<String, Object> stack = jvmManager.getThreadStackTrace(tid);
            if (Boolean.FALSE.equals(stack.get("found"))) {
                ctx.status(404).json(stack);
            } else {
                ctx.json(stack);
            }
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("found", false, "message", "Invalid thread ID: " + idParam));
        }
    }

    private void getJvmThreadDump(Context ctx) {
        Map<String, Object> dump = jvmManager.generateThreadDump();
        String format = ctx.queryParam("format");
        if ("text".equalsIgnoreCase(format) || "raw".equalsIgnoreCase(format)) {
            ctx.contentType("text/plain; charset=utf-8").result((String) dump.get("rawText"));
        } else {
            ctx.json(dump);
        }
    }

    private void getJvmDeadlocks(Context ctx) {
        ctx.json(jvmManager.findDeadlocks());
    }

    private void trimJvmMemory(Context ctx) {
        Map<String, Object> res = jvmManager.trimMemory(() -> {
            int beforeLayouts = layoutCache.size();
            int beforeModules = precomputedModuleInsights.size();
            layoutCache.clear();
            precomputedModuleInsights.clear();
            precomputedModuleResult = null;
            clearTransientScanSnapshot();
            log.info("Trimmed in-memory layout & module caches (cleared {} layouts, {} modules)", beforeLayouts, beforeModules);
        });
        logProcessBanner("JVM_TRIM", "Memory Optimizer", "Caches + GC",
                String.format("Reclaimed %s MB (duration: %d ms)", res.get("freedMb"), res.get("durationMs")));
        ctx.json(res);
    }

    private void getJvmAutoRecovery(Context ctx) {
        ctx.json(heapWatchdog.getStatusAndMetrics());
    }

    private void triggerJvmAutoRecovery(Context ctx) {
        HeapAutoRecoveryManager.AutoRecoveryIncident incident = heapWatchdog.triggerAutoRecovery("MANUAL_API_TRIGGER");
        logProcessBanner("HEAP_RECOVERED", "Heap Watchdog", "Manual Trigger",
                String.format("Reclaimed %.1f MB (duration: %d ms)", incident.reclaimedMb, incident.durationMs));
        ctx.json(incident.toMap());
    }

    private void simulateJvmAutoRecovery(Context ctx) {
        int targetMb = intParam(ctx, "targetMb", 60);
        Map<String, Object> res = heapWatchdog.simulateMemoryPressure(targetMb);
        logProcessBanner("HEAP_SIMULATION", "Memory Watchdog", "Simulation Test",
                String.format("Simulated %d MB load -> Auto-recovery completed", targetMb));
        ctx.json(res);
    }

    private void resetJvmCircuitBreaker(Context ctx) {
        heapWatchdog.resetCircuitBreaker();
        ctx.json(Map.of("status", "ok", "message", "Circuit breaker reset to CLOSED"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Handler: POST /api/scan
    // Body: { "sourcePath": "/absolute/path/to/src", "excludePatterns": ["target", "build", "..."] }
    // ─────────────────────────────────────────────────────────────────────────
    private void startScan(Context ctx) {
        if (heapWatchdog.isCircuitBreakerActive()) {
            ctx.status(503).json(Map.of(
                "error", "Server memory circuit breaker is active. Heap is under heavy pressure (" + heapWatchdog.getCurrentHeapUsagePercentage() + "%).",
                "circuitBreaker", true,
                "message", "Auto-recovery is stabilizing the JVM. Please wait a moment before starting a new scan."
            ));
            return;
        }

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
            orchestrator.submit("delta-scanner", BackgroundTaskOrchestrator.Priority.HIGH, () -> runIncrementalScan(finalPath, finalExcludes, progress));
        } else {
            orchestrator.submit("scanner", BackgroundTaskOrchestrator.Priority.HIGH, () -> runScan(finalPath, finalExcludes, progress));
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
        orchestrator.submit("delta-scanner", BackgroundTaskOrchestrator.Priority.HIGH, () -> runIncrementalScan(finalPath, finalExcludes, progress));

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
                    progress.setPercentage(70 + (int)(f * 6)); // 70% -> 76%
                    progress.setSubProgress(curr, total, "Indexing vertices");
                    progress.setDynamicMetrics(
                        "Graph Vertices", String.format("%,d / %,d", curr, total),
                        "Call Edges", "0 / " + totalCallEdges,
                        "Field Links", "Pending",
                        "Caller Triggers", "Pending"
                    );
                } else if ("Call Graph: Mapping Edges".equals(phase)) {
                    float f = totalCallEdges > 0 ? (float) curr / totalCallEdges : 1f;
                    progress.setPercentage(76 + (int)(f * 6)); // 76% -> 82%
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
            progress.setPercentage(76);
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
                progress.setPercentage(76 + (int)(f * 6)); // 76% -> 82%
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
            progress.setSkippedRecords((int) (dao.getSkippedRecordCount() + result.skippedEntities));

            invalidateGraphCache();
            warmupGraphCache(progress);

            if (cancelRequested) {
                return;
            }

            // Phase 6: Module Dependency Analysis
            progress.setActiveStage("MODULES");
            progress.recordStageStart("MODULES", "Module Dependency Analysis", "Analyzing package architecture, coupling, and circular dependencies");
            progress.setCurrentPhase("Module Dependencies");
            progress.setMessage("Analyzing inter-module relationships & architecture…");
            progress.setPercentage(88);
            progress.setCurrentDetail("Computing module boundaries and touch points…");
            progress.setSubProgress(1, 4, "Analyzing module architecture");
            progress.setDynamicMetrics(
                "Modules", "Analyzing…",
                "Coupling", "Calculating…",
                "Cycles", "Detecting…",
                "Status", "In progress"
            );

            ModuleDependencyAnalyzer.FullModuleDependencyResult moduleResult = precomputeModuleDependencies(progress);
            int modulesCount = (moduleResult != null && moduleResult.overview != null && moduleResult.overview.modules != null)
                ? moduleResult.overview.modules.size() : 0;
            progress.setModulesFound(modulesCount);
            progress.setPercentage(93);

            Map<String, String> moduleMetrics = new LinkedHashMap<>();
            moduleMetrics.put("Modules Indexed", String.valueOf(modulesCount));
            moduleMetrics.put("Inter-Module Links", moduleResult != null && moduleResult.overview != null ? String.valueOf(moduleResult.overview.totalInterModuleTouchPoints) : "0");
            boolean hasEfferentRisk = moduleResult != null && moduleResult.overview != null && moduleResult.overview.modules != null &&
                moduleResult.overview.modules.stream().anyMatch(m -> "High Efferent".equals(m.stabilityRating));
            moduleMetrics.put("Stability Risk", hasEfferentRisk ? "Efferent Risk" : "Stable Core");
            moduleMetrics.put("Status", "Complete");
            progress.recordStageEnd("MODULES", "COMPLETE", String.format("Analyzed %,d modules and inter-package dependencies", modulesCount), moduleMetrics);

            if (cancelRequested) {
                return;
            }

            // Phase 7: Codebase Intelligence Reports Precomputation
            progress.setActiveStage("REPORTS");
            progress.recordStageStart("REPORTS", "Codebase Intelligence Reports", "Generating all 13 architecture, risk, quality, and concurrency reports");
            progress.setCurrentPhase("Generating Reports");
            progress.setMessage("Generating codebase intelligence reports…");
            progress.setPercentage(93);
            progress.setCurrentDetail("Initializing sequential report generation pipeline…");
            progress.setSubProgress(0, 13, "Reports Generation");
            progress.setDynamicMetrics(
                "Reports Ready", "0 / 13",
                "Active Report", "Starting…",
                "Artifacts", "0",
                "Snapshot", "Pending"
            );

            precomputeAllReports(progress, true);

            int reportsCount = cachedReportsJson.size() > 0 ? cachedReportsJson.size() : 13;
            progress.setReportsFound(reportsCount);
            progress.setPercentage(99);

            Map<String, String> reportMetrics = new LinkedHashMap<>();
            reportMetrics.put("Reports Ready", String.format("%d / 13", reportsCount));
            reportMetrics.put("Artifacts", String.valueOf(cachedReportArtifacts.size()));
            reportMetrics.put("Snapshot", "Ready");
            reportMetrics.put("Status", "Complete");
            progress.recordStageEnd("REPORTS", "COMPLETE", String.format("Generated %,d codebase intelligence reports", reportsCount), reportMetrics);

            if (cancelRequested) {
                return;
            }

            // Phase 7b: Structural Inconsistency Detection with Class-Awareness
            try {
                List<CodeType> scanTypes = dao.findAllTypes();
                List<CodeMethod> scanMethods = dao.findAllMethods();
                List<CodeField> scanFields = dao.findAllFields();
                List<CodeRelationship> scanRels = dao.findAllRelationships();
                List<InconsistencyReport> inconsistencies = inconsistencyDetector.detect(scanTypes, scanMethods, scanFields, scanRels);
                dao.batchInsertInconsistencies(inconsistencies);
                log.info("Structural inconsistency scan complete: detected {} issues across {} types", inconsistencies.size(), scanTypes.size());
            } catch (Exception ex) {
                log.warn("Failed to compute structural inconsistencies during scan: {}", ex.getMessage());
            }

            // Phase 8: Complete
            progress.setActiveStage("COMPLETE");
            progress.setPercentage(100);
            progress.setCurrentPhase("Complete");
            progress.setCurrentDetail("All graphs, modules, and intelligence reports ready");
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

        } catch (Throwable e) {
            if (e instanceof OutOfMemoryError || (e.getCause() != null && e.getCause() instanceof OutOfMemoryError)) {
                heapWatchdog.handleTrappedOOM("FullScan (" + sourcePath + ")", e);
                logProcessBanner("SCAN_OOM_RECOVERED", "Full Codebase Scan", sourcePath, "Out of memory trapped; auto-recovery executed");
                try { db.finishBulkLoad(); } catch (Exception ignored) {}
                progress.recordStageEnd(progress.getActiveStage(), "ERROR", "Scan reached heap memory limit. Memory was auto-recovered.", null);
                progress.setStatus(ScanProgress.Status.ERROR);
                progress.setMessage("Scan halted: Java heap memory limit reached. Caches purged & memory stabilized.");
                progress.setErrorDetail("OutOfMemoryError: Java heap space. Recommended: launch with -Xmx4g or higher for large projects.");
                progress.setEndTime(System.currentTimeMillis());
                try { dao.saveScanMeta(progress); } catch (Exception ignored) {}
                return;
            }
            log.error("Scan failed", e);
            logProcessBanner("SCAN_FAILED", "Full Codebase Scan", sourcePath, "Error: " + e.getMessage());
            try { db.finishBulkLoad(); } catch (Exception ignored) {}
            progress.recordStageEnd(progress.getActiveStage(), "ERROR", "Scan failed: " + e.getMessage(), null);
            progress.setStatus(ScanProgress.Status.ERROR);
            progress.setMessage("Scan failed");
            progress.setErrorDetail(e.getMessage());
            progress.setEndTime(System.currentTimeMillis());
            try { dao.saveScanMeta(progress); } catch (Exception ignored) {}
        } finally {
            clearTransientScanSnapshot();
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
                    progress.setPercentage(70 + (int)(f * 6)); // 70% -> 76%
                    progress.setSubProgress(curr, total, "Indexing vertices");
                    progress.setDynamicMetrics(
                        "Graph Vertices", String.format("%,d / %,d", curr, total),
                        "Call Edges", "0 / " + totalCallEdges,
                        "Field Links", "Pending",
                        "Caller Triggers", "Pending"
                    );
                } else if ("Call Graph: Mapping Edges".equals(phase)) {
                    float f = totalCallEdges > 0 ? (float) curr / totalCallEdges : 1f;
                    progress.setPercentage(76 + (int)(f * 6)); // 76% -> 82%
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
            progress.setPercentage(76);
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
                progress.setPercentage(76 + (int)(f * 6)); // 76% -> 82%
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
            progress.setSkippedRecords((int) (dao.getSkippedRecordCount() + result.skippedEntities));

            // Phase 5: Invalidate obsolete cached graph layouts and warm up fresh ones
            invalidateGraphCache();
            warmupGraphCache(progress);

            if (cancelRequested) return;

            // Phase 6: Module Dependency Analysis
            progress.setActiveStage("MODULES");
            progress.recordStageStart("MODULES", "Module Dependency Analysis", "Analyzing package architecture, coupling, and circular dependencies");
            progress.setCurrentPhase("Module Dependencies");
            progress.setMessage("Analyzing inter-module relationships & architecture…");
            progress.setPercentage(88);
            progress.setCurrentDetail("Computing module boundaries and touch points…");
            progress.setSubProgress(1, 4, "Analyzing module architecture");
            progress.setDynamicMetrics(
                "Modules", "Analyzing…",
                "Coupling", "Calculating…",
                "Cycles", "Detecting…",
                "Status", "In progress"
            );

            ModuleDependencyAnalyzer.FullModuleDependencyResult moduleResult = precomputeModuleDependencies(progress);
            int modulesCount = (moduleResult != null && moduleResult.overview != null && moduleResult.overview.modules != null)
                ? moduleResult.overview.modules.size() : 0;
            progress.setModulesFound(modulesCount);
            progress.setPercentage(93);

            Map<String, String> moduleMetrics = new LinkedHashMap<>();
            moduleMetrics.put("Modules Indexed", String.valueOf(modulesCount));
            moduleMetrics.put("Inter-Module Links", moduleResult != null && moduleResult.overview != null ? String.valueOf(moduleResult.overview.totalInterModuleTouchPoints) : "0");
            boolean hasEfferentRisk = moduleResult != null && moduleResult.overview != null && moduleResult.overview.modules != null &&
                moduleResult.overview.modules.stream().anyMatch(m -> "High Efferent".equals(m.stabilityRating));
            moduleMetrics.put("Stability Risk", hasEfferentRisk ? "Efferent Risk" : "Stable Core");
            moduleMetrics.put("Status", "Complete");
            progress.recordStageEnd("MODULES", "COMPLETE", String.format("Analyzed %,d modules and inter-package dependencies", modulesCount), moduleMetrics);

            if (cancelRequested) return;

            // Phase 7: Codebase Intelligence Reports Precomputation
            progress.setActiveStage("REPORTS");
            progress.recordStageStart("REPORTS", "Codebase Intelligence Reports", "Generating all 13 architecture, risk, quality, and concurrency reports");
            progress.setCurrentPhase("Generating Reports");
            progress.setMessage("Generating codebase intelligence reports…");
            progress.setPercentage(93);
            progress.setCurrentDetail("Initializing sequential report generation pipeline…");
            progress.setSubProgress(0, 13, "Reports Generation");
            progress.setDynamicMetrics(
                "Reports Ready", "0 / 13",
                "Active Report", "Starting…",
                "Artifacts", "0",
                "Snapshot", "Pending"
            );

            precomputeAllReports(progress, true);

            int reportsCount = cachedReportsJson.size() > 0 ? cachedReportsJson.size() : 13;
            progress.setReportsFound(reportsCount);
            progress.setPercentage(99);

            Map<String, String> reportMetrics = new LinkedHashMap<>();
            reportMetrics.put("Reports Ready", String.format("%d / 13", reportsCount));
            reportMetrics.put("Artifacts", String.valueOf(cachedReportArtifacts.size()));
            reportMetrics.put("Snapshot", "Ready");
            reportMetrics.put("Status", "Complete");
            progress.recordStageEnd("REPORTS", "COMPLETE", String.format("Generated %,d codebase intelligence reports", reportsCount), reportMetrics);

            if (cancelRequested) return;

            // Phase 8: Complete
            progress.setActiveStage("COMPLETE");
            progress.setCurrentPhase("Complete");
            progress.setCurrentDetail("All graphs, modules, and intelligence reports ready");
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

        } catch (Throwable e) {
            if (e instanceof OutOfMemoryError || (e.getCause() != null && e.getCause() instanceof OutOfMemoryError)) {
                heapWatchdog.handleTrappedOOM("IncrementalScan (" + sourcePath + ")", e);
                logProcessBanner("INCREMENTAL_OOM_RECOVERED", "Incremental Delta Scan", sourcePath, "Out of memory trapped; auto-recovery executed");
                progress.recordStageEnd(progress.getActiveStage(), "ERROR", "Incremental scan reached heap limit. Memory was auto-recovered.", null);
                progress.setStatus(ScanProgress.Status.ERROR);
                progress.setMessage("Incremental scan halted: Java heap memory limit reached. Memory was auto-recovered.");
                progress.setErrorDetail("OutOfMemoryError: Java heap space.");
                progress.setEndTime(System.currentTimeMillis());
                try { dao.saveScanMeta(progress); } catch (Exception ignored) {}
                return;
            }
            log.error("Incremental scan failed", e);
            logProcessBanner("INCREMENTAL_FAILED", "Incremental Delta Scan", sourcePath, "Error: " + e.getMessage());
            progress.recordStageEnd(progress.getActiveStage(), "ERROR", "Incremental scan failed: " + e.getMessage(), null);
            progress.setStatus(ScanProgress.Status.ERROR);
            progress.setMessage("Incremental scan failed");
            progress.setErrorDetail(e.getMessage());
            progress.setEndTime(System.currentTimeMillis());
            try { dao.saveScanMeta(progress); } catch (Exception ignored) {}
        } finally {
            clearTransientScanSnapshot();
        }
    }





    // ─────────────────────────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────────────────────────
    private void getStats(Context ctx) throws Exception {
        ScanProgress sp = scanState.get();
        if ((sp != null && sp.getStatus() == ScanProgress.Status.SCANNING) || db.isBulkLoadInProgress()) {
            Map<String, Object> liveStats = new LinkedHashMap<>();
            liveStats.put("modules", sp != null ? sp.getModulesFound() : 0);
            liveStats.put("reports", sp != null ? sp.getReportsFound() : 0);
            liveStats.put("packages", 0);
            liveStats.put("types", sp != null ? sp.getTypesFound() : 0);
            liveStats.put("classes", sp != null ? sp.getTypesFound() : 0);
            liveStats.put("interfaces", 0);
            liveStats.put("enums", 0);
            liveStats.put("records", 0);
            liveStats.put("fields", sp != null ? sp.getFieldsFound() : 0);
            liveStats.put("methods", sp != null ? sp.getMethodsFound() : 0);
            liveStats.put("relationships", sp != null ? sp.getRelationshipsFound() : 0);
            liveStats.put("inconsistencies", 0);
            liveStats.put("methodsList", Collections.emptyList());
            liveStats.put("typesList", Collections.emptyList());
            liveStats.put("persistentClasses", Collections.emptyList());
            liveStats.put("scanning", true);
            ctx.json(liveStats);
            return;
        }
        Map<String, Object> stats = dao.getStats();
        int modCount = precomputedModuleResult != null && precomputedModuleResult.overview != null && precomputedModuleResult.overview.modules != null
            ? precomputedModuleResult.overview.modules.size()
            : (stats.containsKey("modules") ? ((Number) stats.get("modules")).intValue() : dao.findAllPackages().size());
        stats.put("modules", modCount);
        stats.put("reports", cachedReportsJson.size() > 0 ? cachedReportsJson.size() : 13);
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

        ctx.header("Cache-Control", "private, max-age=60");
        String cacheKey = query.trim().toLowerCase();

        // 1. Direct O(1) in-memory cache hit (precomputed, strong-referenced)
        ModuleDependencyAnalyzer.ModuleDependencyInsights cached = precomputedModuleInsights.get(cacheKey);
        if (cached != null) {
            ctx.json(cached);
            return;
        }

        // 2. Exact or closest package/module lookup across precomputed insights
        ModuleDependencyAnalyzer.ModuleDependencyInsights bestMatch = null;
        int bestMatchLength = 0;
        for (Map.Entry<String, ModuleDependencyAnalyzer.ModuleDependencyInsights> entry : precomputedModuleInsights.entrySet()) {
            String k = entry.getKey();
            if (k.equalsIgnoreCase(cacheKey)) {
                bestMatch = entry.getValue();
                break;
            }
            if ((cacheKey.startsWith(k + ".") || k.startsWith(cacheKey + ".")) && k.length() > bestMatchLength) {
                bestMatch = entry.getValue();
                bestMatchLength = k.length();
            }
        }
        if (bestMatch != null) {
            precomputedModuleInsights.put(cacheKey, bestMatch);
            ctx.json(bestMatch);
            return;
        }

        // 3. If precomputed cache has not run yet, load from disk cache or auto-trigger background precompute
        if (precomputedModuleResult == null) {
            ScanProgress sp = scanState.get();
            if (sp != null && sp.getStatus() == ScanProgress.Status.SCANNING) {
                ctx.status(202).json(Map.of("status", "scanning", "message", "Scan in progress, module dependency analysis pending"));
                return;
            }

            precomputeModuleDependencies(null);
            cached = precomputedModuleInsights.get(cacheKey);
            if (cached != null) {
                ctx.json(cached);
                return;
            }
            for (Map.Entry<String, ModuleDependencyAnalyzer.ModuleDependencyInsights> entry : precomputedModuleInsights.entrySet()) {
                String k = entry.getKey();
                if (k.equalsIgnoreCase(cacheKey) || (cacheKey.startsWith(k + ".") || k.startsWith(cacheKey + "."))) {
                    precomputedModuleInsights.put(cacheKey, entry.getValue());
                    ctx.json(entry.getValue());
                    return;
                }
            }
        }

        // 4. Guaranteed complete fallback: Return a valid empty ModuleDependencyInsights rather than 404
        ModuleDependencyAnalyzer.ModuleDependencyInsights emptyInsights = new ModuleDependencyAnalyzer.ModuleDependencyInsights();
        String simpleName = query.contains(".") ? query.substring(query.lastIndexOf('.') + 1) : query;
        emptyInsights.moduleName = simpleName;
        emptyInsights.packageFqn = query;
        emptyInsights.stabilityRating = "Independent";
        emptyInsights.instability = 0.0;
        emptyInsights.totalTouchPoints = 0;
        emptyInsights.totalInboundTouchPoints = 0;
        emptyInsights.totalOutboundTouchPoints = 0;
        precomputedModuleInsights.put(cacheKey, emptyInsights);
        ctx.json(emptyInsights);
    }

    private void getAllModuleInsights(Context ctx) throws Exception {
        ctx.header("Cache-Control", "private, max-age=60");
        if (precomputedModuleResult == null) {
            precomputeModuleDependencies(null);
        }
        if (precomputedModuleResult != null && precomputedModuleResult.insightsByModule != null) {
            ctx.json(precomputedModuleResult.insightsByModule);
        } else {
            ctx.json(Collections.emptyMap());
        }
    }

    private void getAllModuleDependencies(Context ctx) throws Exception {
        ctx.header("Cache-Control", "private, max-age=60");
        if (precomputedModuleResult != null && precomputedModuleResult.overview != null) {
            ctx.json(precomputedModuleResult.overview);
            return;
        }

        ScanProgress sp = scanState.get();
        if (sp != null && sp.getStatus() == ScanProgress.Status.SCANNING) {
            ctx.status(202).json(Map.of("status", "scanning", "message", "Scan in progress, module dependency overview pending"));
            return;
        }

        ModuleDependencyAnalyzer.FullModuleDependencyResult result = precomputeModuleDependencies(null);
        if (result != null && result.overview != null) {
            ctx.json(result.overview);
        } else {
            ctx.json(new ModuleDependencyAnalyzer.ModuleOverviewPayload());
        }
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

    private void getInconsistencies(Context ctx) throws Exception {
        boolean refresh = Boolean.parseBoolean(ctx.queryParam("refresh"));
        List<InconsistencyReport> reports = refresh ? Collections.emptyList() : dao.findAllInconsistencies();
        if (reports.isEmpty()) {
            List<CodeType> types = dao.findAllTypes();
            List<CodeMethod> methods = dao.findAllMethods();
            List<CodeField> fields = dao.findAllFields();
            List<CodeRelationship> rels = dao.findAllRelationships();
            if (!methods.isEmpty()) {
                reports = inconsistencyDetector.detect(types, methods, fields, rels);
                dao.batchInsertInconsistencies(reports);
            }
        }
        ctx.json(reports);
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

        orchestrator.submit("git-analyzer", BackgroundTaskOrchestrator.Priority.NORMAL, () -> {
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
    // Reports & Exports (Instant Precomputed & Caching Engine)
    // ─────────────────────────────────────────────────────────────────────────

    private void getAllReports(Context ctx) {
        ctx.header("Cache-Control", "private, max-age=60");
        if (cachedReportsJson.isEmpty()) {
            loadReportsFromDiskCache();
        }
        if (reportsPrecomputeRunning.get()) {
            ctx.status(202).json(Map.of(
                "status", "generating",
                "phase", reportsPrecomputePhase.get(),
                "percentage", reportsPrecomputePercentage.get(),
                "message", "Reports generation in progress: " + reportsPrecomputePhase.get(),
                "reports", cachedReportsJson
            ));
            return;
        }
        ctx.json(Map.of(
            "status", "ready",
            "count", cachedReportsJson.size(),
            "reports", cachedReportsJson
        ));
    }

    private void serveReport(Context ctx, String reportKey, String defaultFormat) {
        String format = ctx.queryParam("format");
        if (format == null || format.isBlank()) format = defaultFormat;
        else format = format.trim().toLowerCase();
        if ("md".equals(format)) format = "markdown";

        ctx.header("Cache-Control", "private, max-age=60");

        // 1. Check disk cache first via streaming InputStream (<1ms, 0 heap string allocation)
        String ext = "markdown".equals(format) ? "md" : format;
        File diskFile = new File(getReportsCacheDir(), reportKey + "." + ext);
        if (diskFile.exists() && diskFile.length() > 0) {
            if ("html".equals(format) && !"html-snapshot".equals(reportKey)) {
                try {
                    String sample = Files.readString(diskFile.toPath(), StandardCharsets.UTF_8);
                    if (!sample.contains("report-html-pagination")) {
                        diskFile.delete();
                        cachedReportArtifacts.remove(reportKey + ":html");
                    }
                } catch (Exception ignored) {}
            }
        }
        if (diskFile.exists() && diskFile.length() > 0) {
            String contentType;
            if ("html".equals(format)) contentType = "text/html; charset=UTF-8";
            else if ("csv".equals(format)) contentType = "text/csv; charset=UTF-8";
            else if ("json".equals(format)) contentType = "application/json; charset=UTF-8";
            else contentType = "text/markdown; charset=UTF-8";

            try {
                ctx.contentType(contentType).result(Files.newInputStream(diskFile.toPath()));
                return;
            } catch (Exception e) {
                log.warn("Failed streaming cached report {}.{}: {}", reportKey, ext, e.getMessage());
            }
        }

        // 2. Check in-memory precomputed JSON cache
        if ("json".equals(format)) {
            Object jsonData = cachedReportsJson.get(reportKey);
            if (jsonData != null) {
                ctx.json(jsonData);
                return;
            }
        }

        // 3. Check disk cache if not yet indexed in memory
        if (loadReportsFromDiskCache()) {
            if (diskFile.exists() && diskFile.length() > 0) {
                String contentType;
                if ("html".equals(format)) contentType = "text/html; charset=UTF-8";
                else if ("csv".equals(format)) contentType = "text/csv; charset=UTF-8";
                else if ("json".equals(format)) contentType = "application/json; charset=UTF-8";
                else contentType = "text/markdown; charset=UTF-8";

                try {
                    ctx.contentType(contentType).result(Files.newInputStream(diskFile.toPath()));
                    return;
                } catch (Exception e) {
                    log.warn("Failed streaming cached report {}.{}: {}", reportKey, ext, e.getMessage());
                }
            }
            if ("json".equals(format)) {
                Object jsonData = cachedReportsJson.get(reportKey);
                if (jsonData != null) {
                    ctx.json(jsonData);
                    return;
                }
            }
        }

        // 4. Fallback: If JSON report exists in memory but requested format (e.g. md/html) wasn't rendered yet
        if (cachedReportsJson.containsKey(reportKey)) {
            Object jsonData = cachedReportsJson.get(reportKey);
            if ("json".equals(format)) {
                ctx.json(jsonData);
                return;
            }
            String jsonStr;
            try {
                jsonStr = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonData);
            } catch (Exception ignored) {
                jsonStr = String.valueOf(jsonData);
            }
            if ("html".equals(format)) {
                String fallbackHtml = "<!DOCTYPE html><html><head><title>" + reportKey + "</title></head><body style=\"background:#0b0f19;color:#f1f5f9;font-family:sans-serif;padding:24px;\"><pre>" + jsonStr + "</pre></body></html>";
                ctx.contentType("text/html; charset=UTF-8").result(fallbackHtml);
                return;
            } else if ("markdown".equals(format)) {
                String fallbackMd = "# " + reportKey.toUpperCase() + " REPORT\n\n```json\n" + jsonStr + "\n```\n";
                ctx.contentType("text/markdown; charset=UTF-8").result(fallbackMd);
                return;
            } else {
                ctx.contentType("text/plain; charset=UTF-8").result(jsonStr);
                return;
            }
        }

        // 4. Check if reports precomputation is actively running in background
        if (reportsPrecomputeRunning.get()) {
            if ("json".equals(format)) {
                ctx.status(202).json(Map.of(
                    "status", "generating",
                    "phase", reportsPrecomputePhase.get(),
                    "percentage", reportsPrecomputePercentage.get(),
                    "message", "Reports are currently being generated in background: " + reportsPrecomputePhase.get() + " (" + reportsPrecomputePercentage.get() + "%)"
                ));
            } else if ("html".equals(format)) {
                ctx.status(202).contentType("text/html; charset=UTF-8").result(
                    "<div class=\"reports-loading-state\" style=\"padding:40px; text-align:center; font-family:sans-serif; color:#94a3b8;\">" +
                    "<h3>Precomputing Report in Background</h3>" +
                    "<p>" + reportsPrecomputePhase.get() + " (" + reportsPrecomputePercentage.get() + "%)</p>" +
                    "</div>"
                );
            } else {
                ctx.status(202).contentType("text/plain; charset=UTF-8").result(
                    "Report is being precomputed in background: " + reportsPrecomputePhase.get() + " (" + reportsPrecomputePercentage.get() + "%)"
                );
            }
            return;
        }

        // 5. If reports cache is completely empty, trigger initial background precompute once
        if (cachedReportsJson.isEmpty()) {
            triggerReportsPrecomputeAsync(null, false);
            if ("json".equals(format)) {
                ctx.status(202).json(Map.of(
                    "status", "generating",
                    "phase", "Initializing Precomputation",
                    "percentage", 0,
                    "message", "Reports generation queued in background..."
                ));
            } else if ("html".equals(format)) {
                ctx.status(202).contentType("text/html; charset=UTF-8").result(
                    "<div style=\"padding:40px; text-align:center; font-family:sans-serif; color:#94a3b8;\">" +
                    "<h3>Initializing Precomputation</h3>" +
                    "<p>Reports generation queued in background...</p>" +
                    "</div>"
                );
            } else {
                ctx.status(202).contentType("text/plain; charset=UTF-8").result(
                    "Reports generation queued in background..."
                );
            }
            return;
        }

        // 6. Reports are generated, but this specific reportKey was not found
        ctx.status(404).json(Map.of("error", "Report not found: " + reportKey));
    }

    private void getArchitectureReport(Context ctx) {
        serveReport(ctx, "architecture", "markdown");
    }

    private void getReviewReport(Context ctx) {
        serveReport(ctx, "review", "markdown");
    }

    private void getMetricsReport(Context ctx) {
        serveReport(ctx, "metrics", "csv");
    }

    private void getHtmlSnapshotReport(Context ctx) {
        File snapshotFile = new File(getReportsCacheDir(), "html-snapshot.html");
        if (snapshotFile.exists() && snapshotFile.length() > 0) {
            try {
                ctx.contentType("text/html; charset=UTF-8").result(Files.newInputStream(snapshotFile.toPath()));
                return;
            } catch (Exception e) {
                log.warn("Failed streaming html-snapshot.html: {}", e.getMessage());
            }
        }
        if (loadReportsFromDiskCache()) {
            if (snapshotFile.exists() && snapshotFile.length() > 0) {
                try {
                    ctx.contentType("text/html; charset=UTF-8").result(Files.newInputStream(snapshotFile.toPath()));
                    return;
                } catch (Exception e) {
                    log.warn("Failed streaming html-snapshot.html: {}", e.getMessage());
                }
            }
        }
        if (reportsPrecomputeRunning.get()) {
            ctx.status(202).contentType("text/html; charset=UTF-8").result(
                "<div style=\"padding:40px; text-align:center; font-family:sans-serif; color:#94a3b8;\">" +
                "<h3>Generating Interactive Graph Snapshot</h3>" +
                "<p>" + reportsPrecomputePhase.get() + " (" + reportsPrecomputePercentage.get() + "%)</p>" +
                "</div>"
            );
            return;
        }
        triggerReportsPrecomputeAsync(null, false);
        ctx.status(202).contentType("text/html; charset=UTF-8").result(
            "<div style=\"padding:40px; text-align:center; font-family:sans-serif; color:#94a3b8;\">" +
            "<h3>Initializing Snapshot Generation</h3>" +
            "<p>Offline graph snapshot is being generated in background...</p>" +
            "</div>"
        );
    }

    private void getChangeRiskReport(Context ctx) {
        serveReport(ctx, "change-risk", "json");
    }

    private void getDeadCodeReport(Context ctx) {
        serveReport(ctx, "dead-code", "json");
    }

    private void getCircularDependenciesReport(Context ctx) {
        serveReport(ctx, "circular-dependencies", "json");
    }

    private void getArchetypeGovernanceReport(Context ctx) {
        serveReport(ctx, "archetype-governance", "json");
    }

    private void getTechnicalDebtReport(Context ctx) {
        serveReport(ctx, "technical-debt", "json");
    }

    private void getExecutiveSummaryReport(Context ctx) {
        serveReport(ctx, "executive-summary", "json");
    }

    private void getApiCatalogReport(Context ctx) {
        serveReport(ctx, "api-catalog", "json");
    }

    private void getDatabaseAccessReport(Context ctx) {
        serveReport(ctx, "database-access", "json");
    }

    private void getConcurrencyAuditReport(Context ctx) {
        serveReport(ctx, "concurrency-audit", "json");
    }

    public synchronized boolean regenerateSingleReport(String reportKey) {
        if (reportKey == null || reportKey.isBlank()) return false;
        String key = reportKey.trim().toLowerCase();

        ScanEntitySnapshot snapshot = this.transientScanSnapshot;
        List<CodeType> types;
        List<CodeMethod> methods;
        List<CodeField> fields;
        List<CodeRelationship> rels;
        List<GitMeta> gitMetas;

        try {
            if (snapshot != null && snapshot.types != null && !snapshot.types.isEmpty()) {
                types = snapshot.types;
                methods = (snapshot.methods != null && !snapshot.methods.isEmpty()) ? snapshot.methods : dao.findAllMethods();
                fields = (snapshot.fields != null && !snapshot.fields.isEmpty()) ? snapshot.fields : dao.findAllFields();
                rels = (snapshot.relationships != null && !snapshot.relationships.isEmpty()) ? snapshot.relationships : dao.findAllRelationships();
                gitMetas = (snapshot.gitMetas != null && !snapshot.gitMetas.isEmpty()) ? snapshot.gitMetas : dao.findAllGitMeta();
            } else {
                types = dao.findAllTypes();
                methods = dao.findAllMethods();
                fields = dao.findAllFields();
                rels = dao.findAllRelationships();
                gitMetas = dao.findAllGitMeta();
            }
        } catch (Exception e) {
            log.error("Failed loading entities for single report regeneration ({}): {}", key, e.getMessage(), e);
            return false;
        }

        if (types == null || types.isEmpty()) {
            log.warn("Cannot regenerate report {}: no types scanned", key);
            return false;
        }

        log.info("[SINGLE_REPORT_REGENERATE] Starting single regeneration for report '{}'", key);
        long start = System.currentTimeMillis();

        switch (key) {
            case "architecture": {
                ReportService.ArchitectureReportData data = reportService.buildArchitectureData(types, methods, fields, rels);
                cacheReport("architecture", data,
                    reportService.renderArchitectureHtml(data),
                    reportService.renderArchitectureMarkdown(data),
                    null);
                break;
            }
            case "change-risk": {
                ReportService.ChangeRiskReportData data = reportService.buildChangeRiskData(types, methods, fields, rels, gitMetas);
                cacheReport("change-risk", data,
                    reportService.renderChangeRiskHtml(data),
                    reportService.renderChangeRiskMarkdown(data),
                    reportService.renderChangeRiskCsv(data));
                break;
            }
            case "dead-code": {
                ReportService.DeadCodeReportData data = reportService.buildDeadCodeData(types, methods, fields, rels);
                cacheReport("dead-code", data,
                    reportService.renderDeadCodeHtml(data),
                    reportService.renderDeadCodeMarkdown(data),
                    reportService.renderDeadCodeCsv(data));
                break;
            }
            case "circular-dependencies": {
                ReportService.CircularDependencyReportData data = reportService.buildCircularDependencyData(types, methods, rels);
                cacheReport("circular-dependencies", data,
                    reportService.renderCircularDependencyHtml(data),
                    reportService.renderCircularDependencyMarkdown(data),
                    reportService.renderCircularDependencyCsv(data));
                break;
            }
            case "archetype-governance": {
                ReportService.ArchetypeGovernanceReportData data = reportService.buildArchetypeGovernanceData(types, methods, fields, rels);
                cacheReport("archetype-governance", data,
                    reportService.renderArchetypeGovernanceHtml(data),
                    reportService.renderArchetypeGovernanceMarkdown(data),
                    reportService.renderArchetypeGovernanceCsv(data));
                break;
            }
            case "technical-debt": {
                ReportService.TechnicalDebtReportData data = reportService.buildTechnicalDebtData(types, methods, fields, rels);
                cacheReport("technical-debt", data,
                    reportService.renderTechnicalDebtHtml(data),
                    reportService.renderTechnicalDebtMarkdown(data),
                    reportService.renderTechnicalDebtCsv(data));
                break;
            }
            case "executive-summary": {
                ReportService.ArchitectureReportData arch = reportService.buildArchitectureData(types, methods, fields, rels);
                ReportService.ChangeRiskReportData risk = reportService.buildChangeRiskData(types, methods, fields, rels, gitMetas);
                ReportService.DeadCodeReportData dead = reportService.buildDeadCodeData(types, methods, fields, rels);
                ReportService.CircularDependencyReportData cycles = reportService.buildCircularDependencyData(types, methods, rels);
                ReportService.ArchetypeGovernanceReportData gov = reportService.buildArchetypeGovernanceData(types, methods, fields, rels);
                ReportService.TechnicalDebtReportData debt = reportService.buildTechnicalDebtData(types, methods, fields, rels);

                ReportService.ExecutiveSummaryReportData data = reportService.buildExecutiveSummaryData(
                    types, methods, fields, rels, gitMetas, arch, risk, cycles, gov, dead, debt);
                cacheReport("executive-summary", data,
                    reportService.renderExecutiveSummaryHtml(data),
                    reportService.renderExecutiveSummaryMarkdown(data),
                    reportService.renderExecutiveSummaryCsv(data));
                break;
            }
            case "review": {
                ReportService.ReviewReportData data = reportService.buildReviewReportData(types);
                cacheReport("review", data,
                    reportService.renderReviewHtml(data),
                    reportService.renderReviewMarkdown(data),
                    reportService.renderReviewCsv(data));
                break;
            }
            case "metrics": {
                ReportService.MetricsReportData data = reportService.buildMetricsData(types, methods, fields);
                cacheReport("metrics", data,
                    reportService.renderMetricsHtml(data),
                    reportService.renderMetricsMarkdown(data),
                    reportService.renderMetricsCsv(data));
                break;
            }
            case "api-catalog": {
                ReportService.ApiCatalogReportData data = reportService.buildApiCatalogData(types, methods, rels);
                cacheReport("api-catalog", data,
                    reportService.renderApiCatalogHtml(data),
                    reportService.renderApiCatalogMarkdown(data),
                    reportService.renderApiCatalogCsv(data));
                break;
            }
            case "database-access": {
                ReportService.DatabaseAccessReportData data = reportService.buildDatabaseAccessData(types, methods, fields, rels);
                cacheReport("database-access", data,
                    reportService.renderDatabaseAccessHtml(data),
                    reportService.renderDatabaseAccessMarkdown(data),
                    reportService.renderDatabaseAccessCsv(data));
                break;
            }
            case "concurrency-audit": {
                ReportService.ConcurrencyAuditReportData data = reportService.buildConcurrencyAuditData(types, methods, fields, rels);
                cacheReport("concurrency-audit", data,
                    reportService.renderConcurrencyAuditHtml(data),
                    reportService.renderConcurrencyAuditMarkdown(data),
                    reportService.renderConcurrencyAuditCsv(data));
                break;
            }
            case "html-snapshot": {
                Object fullGraph = callGraph.precomputedFullGraphView(false);
                Object archGraph = callGraph.precomputedArchitectureGraphView(null, null);
                String projectName = (!types.isEmpty() && types.get(0).getPackageFqn() != null && !types.get(0).getPackageFqn().isBlank() ? types.get(0).getPackageFqn() : "Codebase");
                ReportService.ArchitectureReportData archData = reportService.buildArchitectureData(types, methods, fields, rels);

                File snapshotFile = new File(getReportsCacheDir(), "html-snapshot.html");
                boolean snapshotSuccess = false;
                try (BufferedWriter writer = Files.newBufferedWriter(snapshotFile.toPath(), StandardCharsets.UTF_8)) {
                    reportService.writeInteractiveHtmlSnapshot(writer, projectName, fullGraph, archGraph, archData);
                    snapshotSuccess = (snapshotFile.exists() && snapshotFile.length() > 5000);
                } catch (Exception e) {
                    log.error("Failed streaming interactive HTML snapshot: {}", e.getMessage(), e);
                }

                Map<String, Object> snapshotMeta = new LinkedHashMap<>();
                snapshotMeta.put("report", "html-snapshot");
                snapshotMeta.put("name", "Standalone Offline HTML Snapshot");
                snapshotMeta.put("status", snapshotSuccess ? "ready" : "error");
                snapshotMeta.put("file", "html-snapshot.html");
                snapshotMeta.put("sizeBytes", snapshotFile.exists() ? snapshotFile.length() : 0);
                snapshotMeta.put("generatedAt", System.currentTimeMillis());

                cachedReportsJson.put("html-snapshot", snapshotMeta);
                if (snapshotSuccess) {
                    cachedReportArtifacts.add("html-snapshot:html");
                    cachedReportArtifacts.add("html-snapshot:json");
                    writeJsonToFile(new File(getReportsCacheDir(), "html-snapshot.json"), snapshotMeta);
                }
                break;
            }
            default:
                log.warn("[SINGLE_REPORT_REGENERATE] Unknown report key: '{}'", key);
                return false;
        }

        long dur = System.currentTimeMillis() - start;
        log.info("[SINGLE_REPORT_REGENERATE] Successfully regenerated report '{}' in {} ms", key, dur);
        return true;
    }

    private void regenerateReports(Context ctx) {
        String report = ctx.queryParam("report");
        if (report == null || report.isBlank()) {
            try {
                Map<?, ?> body = ctx.bodyAsClass(Map.class);
                if (body != null && body.get("report") != null) {
                    report = String.valueOf(body.get("report"));
                }
            } catch (Exception ignored) {}
        }

        if (report != null && !report.isBlank() && !"all".equalsIgnoreCase(report.trim())) {
            String key = report.trim().toLowerCase();
            long start = System.currentTimeMillis();
            boolean success = regenerateSingleReport(key);
            long dur = System.currentTimeMillis() - start;
            if (success) {
                ctx.json(Map.of(
                    "status", "completed",
                    "report", key,
                    "message", "Report " + key + " regenerated successfully in " + dur + "ms",
                    "durationMs", dur
                ));
            } else {
                ctx.status(400).json(Map.of(
                    "status", "error",
                    "report", key,
                    "message", "Unknown or unhandled report key: " + key
                ));
            }
            return;
        }

        triggerReportsPrecomputeAsync(null, true);
        ctx.json(Map.of(
            "status", "queued",
            "message", "Reports regeneration started in background",
            "running", reportsPrecomputeRunning.get(),
            "phase", reportsPrecomputePhase.get(),
            "percentage", reportsPrecomputePercentage.get()
        ));
    }

    private void getReportsStatus(Context ctx) {
        long lastGen = reportsLastGeneratedTimestamp.get();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", reportsPrecomputeRunning.get());
        status.put("phase", reportsPrecomputePhase.get());
        status.put("percentage", reportsPrecomputePercentage.get());
        status.put("cachedCount", cachedReportsJson.size());
        status.put("cachedKeys", cachedReportsJson.keySet());
        status.put("lastGeneratedTimestamp", lastGen);
        status.put("lastGenerationDurationMs", reportsLastGenerationDurationMs.get());
        if (lastGen > 0) {
            status.put("lastGeneratedFormatted", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(lastGen)));
        }
        ctx.json(status);
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

            serveReport(ctx, type, format);
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

    // ─────────────────────────────────────────────────────────────────────────
    // Diagnostic Flight Recorder & Incident Log Endpoints
    // ─────────────────────────────────────────────────────────────────────────

    private void listDiagnosticLogs(Context ctx) {
        try {
            ctx.json(com.codelens.storage.DiagnosticLogManager.listDiagnosticsSummary());
        } catch (Exception e) {
            log.error("Failed to list diagnostic logs: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to list diagnostic logs: " + e.getMessage()));
        }
    }

    private void getDiagnosticLogContent(Context ctx) {
        try {
            String filename = ctx.pathParam("filename");
            String content = com.codelens.storage.DiagnosticLogManager.readDiagnosticFile(filename);
            boolean download = "true".equalsIgnoreCase(ctx.queryParam("download"));
            if (download) {
                ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            }
            ctx.contentType("text/plain; charset=UTF-8").result(content);
        } catch (java.nio.file.NoSuchFileException e) {
            ctx.status(404).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to read diagnostic log: {}", e.getMessage(), e);
            ctx.status(400).json(Map.of("error", "Failed to read diagnostic log: " + e.getMessage()));
        }
    }

    private void captureDiagnosticSnapshot(Context ctx) {
        try {
            String reason = ctx.queryParam("reason");
            if (reason == null || reason.isBlank()) {
                reason = "Manual User Trigger";
            }
            com.codelens.storage.DiagnosticLogManager.DiagnosticIncidentEntry entry =
                com.codelens.storage.DiagnosticLogManager.captureFullSnapshot(reason, db.getPoolStats());
            ctx.json(Map.of(
                "success", true,
                "incident", entry.toMap(),
                "message", "Captured full diagnostic snapshot to " + entry.filePath
            ));
        } catch (Exception e) {
            log.error("Failed to capture diagnostic snapshot: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to capture diagnostic snapshot: " + e.getMessage()));
        }
    }

    private void clearDiagnosticLogs(Context ctx) {
        try {
            int deleted = com.codelens.storage.DiagnosticLogManager.clearAllDiagnosticFiles();
            ctx.json(Map.of("success", true, "deletedCount", deleted));
        } catch (Exception e) {
            log.error("Failed to clear diagnostic logs: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to clear diagnostic logs: " + e.getMessage()));
        }
    }
}


