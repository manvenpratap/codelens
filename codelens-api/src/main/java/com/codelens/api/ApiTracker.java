package com.codelens.api;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, non-blocking telemetry collector for CodeLens REST APIs.
 * Tracks live endpoint invocation counts, latency, and status codes.
 */
public class ApiTracker {

    public static class RouteMeta {
        public final String method;
        public final String path;
        public final String category;
        public final String description;
        public final boolean canTest;

        public RouteMeta(String method, String path, String category, String description, boolean canTest) {
            this.method = method;
            this.path = path;
            this.category = category;
            this.description = description;
            this.canTest = canTest;
        }

        public String getKey() {
            return method.toUpperCase() + " " + path;
        }
    }

    public static class RouteStats {
        public final AtomicLong calls = new AtomicLong(0);
        public final AtomicLong totalDurationMs = new AtomicLong(0);
        public final AtomicInteger lastStatus = new AtomicInteger(0);
        public final AtomicLong lastCalled = new AtomicLong(0);
    }

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalDurationMs = new AtomicLong(0);
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicLong count2xx = new AtomicLong(0);
    private final AtomicLong count4xx = new AtomicLong(0);
    private final AtomicLong count5xx = new AtomicLong(0);

    private final List<RouteMeta> catalog = new ArrayList<>();
    private final Map<String, RouteStats> statsMap = new ConcurrentHashMap<>();

    public ApiTracker() {
        registerCatalog();
    }

    private void addRoute(String method, String path, String category, String description, boolean canTest) {
        RouteMeta meta = new RouteMeta(method, path, category, description, canTest);
        catalog.add(meta);
        statsMap.put(meta.getKey(), new RouteStats());
    }

    private void registerCatalog() {
        // ── Scan & Ingestion ──────────────────────────────────────────────────
        addRoute("POST", "/api/scan",             "Scan & Ingestion", "Trigger full codebase AST scan and database indexing", false);
        addRoute("POST", "/api/scan/cancel",      "Scan & Ingestion", "Abort currently active scan or analysis worker", true);
        addRoute("GET",  "/api/scan/status",       "Scan & Ingestion", "Retrieve live progress percentage, phase, and file counts", true);
        addRoute("GET",  "/api/scan/changes",      "Scan & Ingestion", "Detect modified, added, or deleted files since last scan", false);
        addRoute("POST", "/api/scan/incremental", "Scan & Ingestion", "Trigger fast incremental delta scan for changed files", false);
        addRoute("GET",  "/api/scan/browse",       "Scan & Ingestion", "Browse server filesystem directories for project folder", true);
        addRoute("POST", "/api/open-folder",      "Scan & Ingestion", "Open project directory in operating system file manager", false);
        addRoute("POST", "/api/shutdown",         "Scan & Ingestion", "Gracefully terminate CodeLens server and flush caches", false);

        // ── Process & Task Hub ────────────────────────────────────────────────
        addRoute("GET",  "/api/processes",                 "Process & Tasks", "List background tasks, JVM heap, thread count & telemetry", true);
        addRoute("POST", "/api/processes/{id}/kill",      "Process & Tasks", "Terminate hanging worker or background thread", false);
        addRoute("POST", "/api/processes/{id}/restart",   "Process & Tasks", "Restart worker or trigger immediate task re-execution", false);

        // ── Database & Storage ────────────────────────────────────────────────
        addRoute("GET",  "/api/database/health",  "Database & Storage", "Check H2 MVStore size, pool metrics, table counts & integrity", true);
        addRoute("POST", "/api/database/recover", "Database & Storage", "Execute self-healing index rebuild, compaction, or orphan purge", false);

        // ── JVM & Telemetry ───────────────────────────────────────────────────
        addRoute("GET",  "/api/jvm/metrics",             "JVM & Telemetry", "Comprehensive JVM telemetry: Heap, Pools, GC, Threads, OS CPU", true);
        addRoute("POST", "/api/jvm/gc",                  "JVM & Telemetry", "Trigger manual garbage collection (System.gc()) & report reclaimed MB", true);
        addRoute("GET",  "/api/jvm/threads",              "JVM & Telemetry", "Live thread list with states, CPU time, locks, and top stack frame", true);
        addRoute("GET",  "/api/jvm/threads/{id}/stack",  "JVM & Telemetry", "Inspect stack trace of an individual JVM thread", false);
        addRoute("GET",  "/api/jvm/thread-dump",          "JVM & Telemetry", "Generate full diagnostic JVM thread dump for export", true);
        addRoute("GET",  "/api/jvm/deadlocks",            "JVM & Telemetry", "Scan JVM for deadlocked monitor and synchronizer threads", true);
        addRoute("POST", "/api/jvm/trim-memory",         "JVM & Telemetry", "Evict in-memory layout & module caches and run garbage collection", true);

        // ── Packages & Modules ────────────────────────────────────────────────
        addRoute("GET",  "/api/packages",                    "Packages & Modules", "List all detected Java packages with hierarchy", true);
        addRoute("GET",  "/api/packages/{fqn}/types",        "Packages & Modules", "Retrieve types declared within package", false);
        addRoute("GET",  "/api/packages/{fqn}/dependencies", "Packages & Modules", "Package-level afferent & efferent dependencies", false);
        addRoute("GET",  "/api/modules/dependencies",        "Packages & Modules", "Complete module-to-module dependency matrix", true);
        addRoute("GET",  "/api/modules/{name}/dependencies", "Packages & Modules", "Specific module dependencies and couplings", false);

        // ── Types & Classes ───────────────────────────────────────────────────
        addRoute("GET",  "/api/types",     "Types & Classes", "List types with kind filter (CLASS, INTERFACE, ENUM, RECORD)", true);
        addRoute("GET",  "/api/types/{id}", "Types & Classes", "Detailed type metadata, members, annotations, and hierarchy", false);

        // ── Methods & Call Graph ──────────────────────────────────────────────
        addRoute("GET",  "/api/methods/{id}",         "Methods & Call Graph", "Method details, CC complexity, parameters, and return type", false);
        addRoute("GET",  "/api/methods/{id}/callers", "Methods & Call Graph", "Retrieve all direct upstream callers of method", false);
        addRoute("GET",  "/api/methods/{id}/callees", "Methods & Call Graph", "Retrieve all direct downstream callees of method", false);
        addRoute("GET",  "/api/methods/{id}/graph",   "Methods & Call Graph", "Multi-hop call graph rooted at method (1–15 hops / Max)", false);
        addRoute("GET",  "/api/graph/all",            "Methods & Call Graph", "Full codebase 2D/3D force-directed topology graph", true);
        addRoute("GET",  "/api/graph/architecture",   "Methods & Call Graph", "Architectural package/module dependency graph", true);
        addRoute("GET",  "/api/graph/precomputed",    "Methods & Call Graph", "Pre-calculated Sunflower spiral layout models", true);
        addRoute("GET",  "/api/graph/export-json",    "Methods & Call Graph", "Export raw graph JSON for external analysis", true);
        addRoute("GET",  "/api/graph/dsm",            "Methods & Call Graph", "Dependency Structure Matrix (DSM) data", true);
        addRoute("GET",  "/api/graph/treemap",        "Methods & Call Graph", "Hierarchical package/class size treemap", true);
        addRoute("GET",  "/api/graph/hub-explorer",   "Methods & Call Graph", "Radial Hub Explorer caller/callee drilldown", false);

        // ── Critical Path & Persistent Classes ────────────────────────────────
        addRoute("GET",  "/api/analysis/persistent-classes", "Critical Path", "Identify persistent entities mapped to database tables", true);
        addRoute("GET",  "/api/analysis/critical-path",       "Critical Path", "Trace critical execution paths leading to persistent entities", false);

        // ── Fields & Field Impact ─────────────────────────────────────────────
        addRoute("GET",  "/api/fields/{id}",        "Fields & Impact", "Field details, type, visibility, and modifiers", false);
        addRoute("GET",  "/api/fields/{id}/impact", "Fields & Impact", "Field propagation graph showing all readers and writers", false);

        // ── Code Review & Quality ─────────────────────────────────────────────
        addRoute("POST", "/api/review", "Code Review", "Rule-based architectural and anti-pattern code review", false);

        // ── Search ────────────────────────────────────────────────────────────
        addRoute("GET",  "/api/search", "Search", "Full-text Lucene + SQL entity search across codebase", false);

        // ── Analyst Notes ─────────────────────────────────────────────────────
        addRoute("GET",    "/api/notes/{entityFqn}", "Analyst Notes", "Retrieve analyst notes on entity", false);
        addRoute("POST",   "/api/notes",             "Analyst Notes", "Create or update analyst note", false);
        addRoute("DELETE", "/api/notes/{id}",        "Analyst Notes", "Delete analyst note", false);

        // ── Files ─────────────────────────────────────────────────────────────
        addRoute("GET",  "/api/files/read",  "Files", "Read source file contents for preview", false);
        addRoute("POST", "/api/files/write", "Files", "Write patch or edit directly to file", false);

        // ── Git Analytics ─────────────────────────────────────────────────────
        addRoute("GET",  "/api/git/meta/{entityFqn}", "Git Analytics", "Blame and commit history for specific entity", false);
        addRoute("GET",  "/api/git/summary",          "Git Analytics", "Overall repository commit churn and author statistics", true);
        addRoute("POST", "/api/git/validate",         "Git Analytics", "Validate if workspace path is a valid Git repository", false);
        addRoute("POST", "/api/git/analyze",          "Git Analytics", "Trigger deep commit history and churn analysis", false);
        addRoute("GET",  "/api/git/status",           "Git Analytics", "Current Git history analysis progress status", true);

        // ── Reports & Exports ─────────────────────────────────────────────────
        addRoute("GET",  "/api/reports/architecture",          "Reports", "Architecture coupling and modularity report", true);
        addRoute("GET",  "/api/reports/change-risk",           "Reports", "Change risk and high-churn hotspot report", true);
        addRoute("GET",  "/api/reports/dead-code",             "Reports", "Unreachable methods and dead code analysis report", true);
        addRoute("GET",  "/api/reports/circular-dependencies", "Reports", "Circular dependency cycle detection report", true);
        addRoute("GET",  "/api/reports/archetype-governance",  "Reports", "Archetype compliance and governance report", true);
        addRoute("GET",  "/api/reports/review",                "Reports", "Consolidated code review report", true);
        addRoute("GET",  "/api/reports/metrics",               "Reports", "Comprehensive codebase metrics report", true);
        addRoute("GET",  "/api/reports/html-snapshot",         "Reports", "Generate standalone interactive HTML snapshot", true);
        addRoute("GET",  "/api/reports/download",              "Reports", "Download generated report in requested format", false);

        // ── Configuration & Scope ─────────────────────────────────────────────
        addRoute("GET",  "/api/config",          "Config & Scope", "Read active server configuration (.conf)", true);
        addRoute("POST", "/api/config",          "Config & Scope", "Save configuration changes", false);
        addRoute("GET",  "/api/config/export",   "Config & Scope", "Export configuration as JSON", true);
        addRoute("POST", "/api/config/import",   "Config & Scope", "Import configuration from JSON", false);
        addRoute("POST", "/api/config/reset",    "Config & Scope", "Reset configuration to defaults", false);
        addRoute("POST", "/api/scope/exclude",   "Config & Scope", "Exclude package, class, or method from analysis scope", false);
        addRoute("GET",  "/api/scope/excluded",  "Config & Scope", "List currently excluded scopes", true);
        addRoute("POST", "/api/scope/restore",   "Config & Scope", "Restore previously excluded scope", false);
        addRoute("POST", "/api/scope/clear",     "Config & Scope", "Clear all exclusion rules", false);

        // ── System & Stats ────────────────────────────────────────────────────
        addRoute("GET",  "/api/stats",  "System", "Overall codebase entities count summary", true);
        addRoute("GET",  "/api/readme", "System", "Rendered README markdown documentation", true);
    }

    public void recordRequestStart(String method, String path) {
        activeRequests.incrementAndGet();
    }

    public void recordRequestEnd(String method, String matchedPath, int statusCode, long durationMs) {
        activeRequests.decrementAndGet();
        totalRequests.incrementAndGet();
        totalDurationMs.addAndGet(durationMs);

        if (statusCode >= 200 && statusCode < 300) {
            count2xx.incrementAndGet();
        } else if (statusCode >= 400 && statusCode < 500) {
            count4xx.incrementAndGet();
        } else if (statusCode >= 500) {
            count5xx.incrementAndGet();
        }

        if (matchedPath != null && !matchedPath.isBlank()) {
            String key = method.toUpperCase() + " " + matchedPath;
            RouteStats stats = statsMap.computeIfAbsent(key, k -> new RouteStats());
            stats.calls.incrementAndGet();
            stats.totalDurationMs.addAndGet(durationMs);
            stats.lastStatus.set(statusCode);
            stats.lastCalled.set(System.currentTimeMillis());
        }
    }

    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        long reqs = totalRequests.get();
        long dur = totalDurationMs.get();
        double avgLatency = reqs > 0 ? Math.round((dur / (double) reqs) * 10.0) / 10.0 : 0.0;

        summary.put("totalEndpoints", catalog.size());
        summary.put("totalRequests", reqs);
        summary.put("activeRequests", Math.max(0, activeRequests.get()));
        summary.put("avgLatencyMs", avgLatency);
        summary.put("status2xx", count2xx.get());
        summary.put("status4xx", count4xx.get());
        summary.put("status5xx", count5xx.get());
        return summary;
    }

    public List<Map<String, Object>> getEndpoints() {
        List<Map<String, Object>> list = new ArrayList<>(catalog.size());
        for (RouteMeta meta : catalog) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("method", meta.method);
            item.put("path", meta.path);
            item.put("category", meta.category);
            item.put("description", meta.description);
            item.put("canTest", meta.canTest);

            RouteStats stats = statsMap.get(meta.getKey());
            long calls = stats != null ? stats.calls.get() : 0;
            long dur = stats != null ? stats.totalDurationMs.get() : 0;
            double avg = calls > 0 ? Math.round((dur / (double) calls) * 10.0) / 10.0 : 0.0;
            int lastStatus = stats != null ? stats.lastStatus.get() : 0;
            long lastCalled = stats != null ? stats.lastCalled.get() : 0;

            item.put("calls", calls);
            item.put("avgLatencyMs", avg);
            item.put("lastStatus", lastStatus);
            item.put("lastCalled", lastCalled);

            list.add(item);
        }
        return list;
    }
}
