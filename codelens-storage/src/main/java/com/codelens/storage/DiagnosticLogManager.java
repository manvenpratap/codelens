package com.codelens.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Automated Diagnostic Flight Recorder & Incident Dump Manager.
 *
 * Automatically generates persistent, self-contained diagnostic incident logs under
 * {@code ./codelens-data/diagnostics/} whenever any of the following events occur:
 * <ul>
 *   <li><b>HEAP_SPACE</b> - OutOfMemoryError or Critical/Emergency heap pressure recovery</li>
 *   <li><b>CONNECTION_LEAK</b> - JDBC connection leak detection & forced eviction or H2 query timeout</li>
 *   <li><b>CRASH</b> - Uncaught thread exception, StackOverflowError, or fatal JVM error</li>
 *   <li><b>FAILURE</b> - Unhandled HTTP 500 API exception, scan pipeline failure, or storage error</li>
 *   <li><b>SNAPSHOT</b> - On-demand full system diagnostic bundle (JVM heap, pools, DB leases, thread dump)</li>
 * </ul>
 */
public class DiagnosticLogManager {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticLogManager.class);

    private static final DateTimeFormatter FILE_TS_FMT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter HUMAN_TS_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final int MAX_DIAGNOSTIC_FILES = 100;
    private static final int MAX_IN_MEMORY_INDEX = 100;

    private static volatile Path diagnosticsDir = Paths.get("./codelens-data/diagnostics");
    private static final AtomicLong sequence = new AtomicLong(1);
    private static final Deque<DiagnosticIncidentEntry> recentIncidents = new ConcurrentLinkedDeque<>();

    public static class DiagnosticIncidentEntry {
        public String id;
        public String category;     // HEAP_SPACE, CONNECTION_LEAK, CRASH, FAILURE, SNAPSHOT
        public String severity;     // CRITICAL, ERROR, WARNING, INFO
        public long timestamp;
        public String timestampFormatted;
        public String title;
        public String summary;
        public String fileName;
        public String filePath;
        public long sizeBytes;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("category", category);
            m.put("severity", severity);
            m.put("timestamp", timestamp);
            m.put("timestampFormatted", timestampFormatted);
            m.put("title", title);
            m.put("summary", summary);
            m.put("fileName", fileName);
            m.put("filePath", filePath);
            m.put("sizeBytes", sizeBytes);
            return m;
        }
    }

    public static synchronized void configureDataDir(String baseDataDir) {
        if (baseDataDir != null && !baseDataDir.isBlank()) {
            diagnosticsDir = Paths.get(baseDataDir, "diagnostics");
        }
        ensureDirectory();
        reloadExistingFilesIntoIndex();
    }

    public static Path getDiagnosticsDir() {
        ensureDirectory();
        return diagnosticsDir;
    }

    private static void ensureDirectory() {
        try {
            if (!Files.exists(diagnosticsDir)) {
                Files.createDirectories(diagnosticsDir);
            }
        } catch (IOException e) {
            log.warn("Could not create diagnostics directory {}: {}", diagnosticsDir, e.getMessage());
        }
    }

    /**
     * Records a Heap Space / Memory Pressure or OutOfMemoryError diagnostic dump.
     */
    public static DiagnosticIncidentEntry recordHeapIssue(String incidentId,
                                                          String trigger,
                                                          long heapBeforeMb,
                                                          long heapAfterMb,
                                                          long heapMaxMb,
                                                          int pctBefore,
                                                          int pctAfter,
                                                          double reclaimedMb,
                                                          long durationMs,
                                                          boolean circuitBreakerOpen,
                                                          List<String> actions,
                                                          Throwable oomCause) {
        String severity = (oomCause != null || pctBefore >= 90 || circuitBreakerOpen) ? "CRITICAL" : "WARNING";
        String title = (oomCause != null)
            ? "OutOfMemoryError Trapped & Recovered (" + trigger + ")"
            : "High Heap Memory Pressure Recovery (" + trigger + ")";
        String summary = String.format(
            "Heap %d MB (%d%%) -> %d MB (%d%%) of %d MB max | Reclaimed %.1f MB in %d ms | Circuit Breaker: %s",
            heapBeforeMb, pctBefore, heapAfterMb, pctAfter, heapMaxMb, reclaimedMb, durationMs,
            circuitBreakerOpen ? "OPEN" : "CLOSED"
        );

        StringBuilder body = new StringBuilder(8192);
        appendHeader(body, "HEAP_SPACE", severity, title, summary);

        body.append("-- 1. HEAP RECOVERY TELEMETRY -------------------------------------------------\n");
        body.append("Incident ID          : ").append(incidentId != null ? incidentId : "N/A").append("\n");
        body.append("Trigger Source       : ").append(trigger).append("\n");
        body.append("Heap Before Recovery : ").append(heapBeforeMb).append(" MB (").append(pctBefore).append("%)\n");
        body.append("Heap After Recovery  : ").append(heapAfterMb).append(" MB (").append(pctAfter).append("%)\n");
        body.append("Max Heap Configured  : ").append(heapMaxMb).append(" MB\n");
        body.append("Memory Reclaimed     : ").append(reclaimedMb).append(" MB\n");
        body.append("Recovery Duration    : ").append(durationMs).append(" ms\n");
        body.append("Circuit Breaker      : ").append(circuitBreakerOpen ? "OPEN (Throttling heavy requests)" : "CLOSED (Normal)").append("\n\n");

        if (actions != null && !actions.isEmpty()) {
            body.append("-- 2. RECOVERY ACTIONS EXECUTED -----------------------------------------------\n");
            for (int i = 0; i < actions.size(); i++) {
                body.append(String.format("  [%d] %s\n", i + 1, actions.get(i)));
            }
            body.append("\n");
        }

        if (oomCause != null) {
            body.append("-- 3. INTERCEPTED OUT-OF-MEMORY STACK TRACE -----------------------------------\n");
            body.append(formatStackTrace(oomCause)).append("\n");
        }

        appendJvmMemoryAndGcSnapshot(body);
        appendThreadSnapshot(body, false);

        return writeIncidentFile("heap", "HEAP_SPACE", severity, title, summary, body.toString());
    }

    /**
     * Records a JDBC Connection Leak detection & eviction or database lock/timeout diagnostic dump.
     */
    public static DiagnosticIncidentEntry recordConnectionLeak(String reason,
                                                               String threadName,
                                                               long threadId,
                                                               long holdDurationMs,
                                                               long idleDurationMs,
                                                               String allocationSite,
                                                               StackTraceElement[] allocationStack,
                                                               Map<String, Object> poolStats,
                                                               Throwable sqlError) {
        String severity = (holdDurationMs >= 180_000 || sqlError != null) ? "ERROR" : "WARNING";
        String title = "Database Connection Leak Evicted (" + reason + ")";
        String summary = String.format(
            "Thread '%s' (id=%d) held connection for %d ms (idle %d ms) at %s",
            threadName, threadId, holdDurationMs, idleDurationMs, allocationSite
        );

        StringBuilder body = new StringBuilder(8192);
        appendHeader(body, "CONNECTION_LEAK", severity, title, summary);

        body.append("-- 1. CONNECTION LEASE DIAGNOSTICS --------------------------------------------\n");
        body.append("Eviction Reason      : ").append(reason).append("\n");
        body.append("Holding Thread       : ").append(threadName).append(" (Thread ID: ").append(threadId).append(")\n");
        body.append("Lease Hold Duration  : ").append(holdDurationMs).append(" ms\n");
        body.append("Lease Idle Duration  : ").append(idleDurationMs).append(" ms\n");
        body.append("Caller Allocation    : ").append(allocationSite).append("\n\n");

        if (poolStats != null && !poolStats.isEmpty()) {
            body.append("-- 2. HIKARICP CONNECTION POOL STATE ------------------------------------------\n");
            poolStats.forEach((k, v) -> body.append(String.format("  %-22s : %s\n", k, v)));
            body.append("\n");
        }

        if (allocationStack != null && allocationStack.length > 0) {
            body.append("-- 3. CONNECTION BORROW ALLOCATION STACK TRACE --------------------------------\n");
            for (StackTraceElement el : allocationStack) {
                body.append("    at ").append(el.toString()).append("\n");
            }
            body.append("\n");
        }

        if (sqlError != null) {
            body.append("-- 4. ASSOCIATED SQL EXCEPTION / TIMEOUT --------------------------------------\n");
            body.append(formatStackTrace(sqlError)).append("\n");
        }

        appendJvmMemoryAndGcSnapshot(body);
        appendThreadSnapshot(body, false);

        return writeIncidentFile("db-leak", "CONNECTION_LEAK", severity, title, summary, body.toString());
    }

    /**
     * Records an uncaught thread crash, fatal JVM error, or unhandled API/Scan failure.
     */
    public static DiagnosticIncidentEntry recordCrashOrFailure(String category,
                                                               String sourceContext,
                                                               Throwable error,
                                                               Map<String, Object> extraMetadata) {
        String normalizedCat = (category != null && !category.isBlank()) ? category.toUpperCase() : "FAILURE";
        boolean isCrash = "CRASH".equals(normalizedCat) || (error instanceof Error);
        String severity = isCrash ? "CRITICAL" : "ERROR";
        String errClass = error != null ? error.getClass().getSimpleName() : "UnknownError";
        String errMsg = (error != null && error.getMessage() != null) ? error.getMessage() : "No message";

        String title = (isCrash ? "Fatal Crash / Error in " : "Failure in ") + sourceContext + " [" + errClass + "]";
        String summary = String.format("%s: %s (Context: %s)", errClass, truncate(errMsg, 140), sourceContext);

        StringBuilder body = new StringBuilder(12288);
        appendHeader(body, normalizedCat, severity, title, summary);

        body.append("-- 1. FAILURE / CRASH CONTEXT -------------------------------------------------\n");
        body.append("Source Context       : ").append(sourceContext).append("\n");
        body.append("Exception Type       : ").append(error != null ? error.getClass().getName() : "N/A").append("\n");
        body.append("Exception Message    : ").append(errMsg).append("\n");
        body.append("Executing Thread     : ").append(Thread.currentThread().getName())
            .append(" (id=").append(Thread.currentThread().getId()).append(")\n");

        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            extraMetadata.forEach((k, v) -> body.append(String.format("%-20s : %s\n", k, v)));
        }
        body.append("\n");

        if (error != null) {
            body.append("-- 2. FULL EXCEPTION STACK TRACE & ROOT CAUSE ---------------------------------\n");
            body.append(formatStackTrace(error)).append("\n");
        }

        appendJvmMemoryAndGcSnapshot(body);
        appendThreadSnapshot(body, true);

        String prefix = isCrash ? "crash" : "failure";
        return writeIncidentFile(prefix, normalizedCat, severity, title, summary, body.toString());
    }

    /**
     * Records a full on-demand diagnostic snapshot (JVM heap, GC, HikariCP pool, full thread dump).
     */
    public static DiagnosticIncidentEntry captureFullSnapshot(String triggerReason, Map<String, Object> dbDiagnostics) {
        String title = "System Diagnostic Flight Recorder Snapshot (" + (triggerReason != null ? triggerReason : "Manual") + ")";
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long usedMb = heap.getUsed() / (1024 * 1024);
        long maxMb = (heap.getMax() > 0 ? heap.getMax() : heap.getCommitted()) / (1024 * 1024);
        int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
        String summary = String.format("Heap: %d / %d MB | Live Threads: %d | Trigger: %s",
            usedMb, maxMb, threadCount, triggerReason != null ? triggerReason : "Manual");

        StringBuilder body = new StringBuilder(16384);
        appendHeader(body, "SNAPSHOT", "INFO", title, summary);

        if (dbDiagnostics != null && !dbDiagnostics.isEmpty()) {
            body.append("-- 1. DATABASE & CONNECTION POOL DIAGNOSTICS ----------------------------------\n");
            dbDiagnostics.forEach((k, v) -> body.append(String.format("  %-22s : %s\n", k, v)));
            body.append("\n");
        }

        appendJvmMemoryAndGcSnapshot(body);
        appendThreadSnapshot(body, true);

        return writeIncidentFile("snapshot", "SNAPSHOT", "INFO", title, summary, body.toString());
    }

    private static synchronized DiagnosticIncidentEntry writeIncidentFile(String filePrefix,
                                                                          String category,
                                                                          String severity,
                                                                          String title,
                                                                          String summary,
                                                                          String content) {
        ensureDirectory();
        long now = System.currentTimeMillis();
        long seq = sequence.getAndIncrement();
        String id = String.format("DIAG-%d-%03d", now % 1000000, seq % 1000);
        String fileName = String.format("incident-%s-%s-%03d.log",
            filePrefix, FILE_TS_FMT.format(Instant.ofEpochMilli(now)), seq % 1000);

        Path filePath = diagnosticsDir.resolve(fileName);
        long sizeBytes = 0;
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            Files.write(filePath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            sizeBytes = bytes.length;
            log.warn("[DIAGNOSTIC-LOG-CREATED] Category={} | Severity={} | File={} ({} bytes) | {}",
                category, severity, filePath.toAbsolutePath(), sizeBytes, summary);
        } catch (IOException e) {
            log.error("Failed to write diagnostic log file {}: {}", filePath, e.getMessage());
        }

        DiagnosticIncidentEntry entry = new DiagnosticIncidentEntry();
        entry.id = id;
        entry.category = category;
        entry.severity = severity;
        entry.timestamp = now;
        entry.timestampFormatted = HUMAN_TS_FMT.format(Instant.ofEpochMilli(now));
        entry.title = title;
        entry.summary = summary;
        entry.fileName = fileName;
        entry.filePath = filePath.toAbsolutePath().toString();
        entry.sizeBytes = sizeBytes;

        recentIncidents.addFirst(entry);
        while (recentIncidents.size() > MAX_IN_MEMORY_INDEX) {
            recentIncidents.removeLast();
        }

        pruneOldDiagnosticFiles();
        return entry;
    }

    private static void appendHeader(StringBuilder sb, String category, String severity, String title, String summary) {
        sb.append("+====================================================================================+\n");
        sb.append("| CODELENS DIAGNOSTIC INCIDENT FLIGHT RECORDER                                       |\n");
        sb.append("+====================================================================================+\n");
        sb.append("Timestamp            : ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))).append("\n");
        sb.append("Category             : ").append(category).append("\n");
        sb.append("Severity             : ").append(severity).append("\n");
        sb.append("Title                : ").append(title).append("\n");
        sb.append("Summary              : ").append(summary).append("\n");
        sb.append("JVM Uptime           : ").append(ManagementFactory.getRuntimeMXBean().getUptime() / 1000).append(" seconds\n");
        sb.append("OS / Arch            : ").append(System.getProperty("os.name")).append(" (")
          .append(System.getProperty("os.arch")).append(") / Java ").append(System.getProperty("java.version")).append("\n\n");
    }

    private static void appendJvmMemoryAndGcSnapshot(StringBuilder sb) {
        sb.append("-- JVM MEMORY & GARBAGE COLLECTION SNAPSHOT -----------------------------------\n");
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();

        sb.append(String.format("Heap Memory          : Used=%d MB, Committed=%d MB, Max=%d MB\n",
            heap.getUsed() / (1024 * 1024),
            heap.getCommitted() / (1024 * 1024),
            (heap.getMax() > 0 ? heap.getMax() : heap.getCommitted()) / (1024 * 1024)));
        sb.append(String.format("Non-Heap / Metaspace : Used=%d MB, Committed=%d MB\n",
            nonHeap.getUsed() / (1024 * 1024),
            nonHeap.getCommitted() / (1024 * 1024)));

        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = pool.getUsage();
            if (u != null) {
                sb.append(String.format("  Pool %-20s : Used=%d MB / Max=%d MB (%s)\n",
                    pool.getName(),
                    u.getUsed() / (1024 * 1024),
                    (u.getMax() > 0 ? u.getMax() : u.getCommitted()) / (1024 * 1024),
                    pool.getType()));
            }
        }

        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            sb.append(String.format("  GC %-22s : Collections=%d, TotalTime=%d ms\n",
                gc.getName(), gc.getCollectionCount(), gc.getCollectionTime()));
        }
        sb.append("\n");
    }

    private static void appendThreadSnapshot(StringBuilder sb, boolean fullStackTraces) {
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        long[] deadlocked = tm.findDeadlockedThreads();
        sb.append("-- JVM THREAD & DEADLOCK DIAGNOSTICS ------------------------------------------\n");
        sb.append("Live Threads         : ").append(tm.getThreadCount())
          .append(" (Peak: ").append(tm.getPeakThreadCount())
          .append(", Daemon: ").append(tm.getDaemonThreadCount()).append(")\n");
        sb.append("Deadlocked Threads   : ").append(deadlocked != null ? deadlocked.length : 0).append("\n\n");

        ThreadInfo[] infos = tm.dumpAllThreads(false, false);
        int shown = 0;
        for (ThreadInfo ti : infos) {
            if (ti == null) continue;
            // Prioritize RUNNABLE, BLOCKED, or CodeLens worker threads
            boolean isInteresting = ti.getThreadState() == Thread.State.RUNNABLE
                || ti.getThreadState() == Thread.State.BLOCKED
                || ti.getThreadName().toLowerCase().contains("codelens")
                || ti.getThreadName().toLowerCase().contains("hikari")
                || ti.getThreadName().toLowerCase().contains("qtp");
            if (!fullStackTraces && !isInteresting && shown > 20) continue;

            sb.append(String.format("\"%s\" #%d state=%s",
                ti.getThreadName(), ti.getThreadId(), ti.getThreadState()));
            if (ti.getLockName() != null) {
                sb.append(" waiting on ").append(ti.getLockName());
            }
            sb.append("\n");

            StackTraceElement[] st = ti.getStackTrace();
            int maxFrames = fullStackTraces ? Math.min(st.length, 25) : Math.min(st.length, 8);
            for (int i = 0; i < maxFrames; i++) {
                sb.append("    at ").append(st[i].toString()).append("\n");
            }
            if (st.length > maxFrames) {
                sb.append("    ... (").append(st.length - maxFrames).append(" more frames)\n");
            }
            sb.append("\n");
            shown++;
            if (shown >= 45) break;
        }
    }

    private static String formatStackTrace(Throwable t) {
        if (t == null) return "";
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    private static void pruneOldDiagnosticFiles() {
        try (Stream<Path> stream = Files.list(diagnosticsDir)) {
            List<Path> files = stream
                .filter(p -> p.getFileName().toString().endsWith(".log"))
                .sorted(Comparator.comparingLong((Path p) -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                        return 0L;
                    }
                }).reversed())
                .collect(Collectors.toList());

            if (files.size() > MAX_DIAGNOSTIC_FILES) {
                for (int i = MAX_DIAGNOSTIC_FILES; i < files.size(); i++) {
                    Files.deleteIfExists(files.get(i));
                }
            }
        } catch (Exception ignored) {}
    }

    private static synchronized void reloadExistingFilesIntoIndex() {
        ensureDirectory();
        recentIncidents.clear();
        try (Stream<Path> stream = Files.list(diagnosticsDir)) {
            List<Path> files = stream
                .filter(p -> p.getFileName().toString().endsWith(".log"))
                .sorted(Comparator.comparingLong((Path p) -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                        return 0L;
                    }
                }).reversed())
                .limit(MAX_IN_MEMORY_INDEX)
                .collect(Collectors.toList());

            for (Path f : files) {
                DiagnosticIncidentEntry entry = parseFileHeader(f);
                if (entry != null) {
                    recentIncidents.addLast(entry);
                }
            }
        } catch (Exception e) {
            log.debug("Could not reload existing diagnostic files: {}", e.getMessage());
        }
    }

    private static DiagnosticIncidentEntry parseFileHeader(Path file) {
        try {
            String fileName = file.getFileName().toString();
            long lastMod = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            List<String> headLines = Files.lines(file, StandardCharsets.UTF_8).limit(14).collect(Collectors.toList());

            String category = fileName.contains("-heap-") ? "HEAP_SPACE"
                : fileName.contains("-db-leak-") ? "CONNECTION_LEAK"
                : fileName.contains("-crash-") ? "CRASH"
                : fileName.contains("-snapshot-") ? "SNAPSHOT" : "FAILURE";
            String severity = "WARNING";
            String title = fileName;
            String summary = "Diagnostic incident log";

            for (String line : headLines) {
                if (line.startsWith("Category             :")) category = line.substring(23).trim();
                else if (line.startsWith("Severity             :")) severity = line.substring(23).trim();
                else if (line.startsWith("Title                :")) title = line.substring(23).trim();
                else if (line.startsWith("Summary              :")) summary = line.substring(23).trim();
            }

            DiagnosticIncidentEntry entry = new DiagnosticIncidentEntry();
            entry.id = "DIAG-" + (lastMod % 1000000);
            entry.category = category;
            entry.severity = severity;
            entry.timestamp = lastMod;
            entry.timestampFormatted = HUMAN_TS_FMT.format(Instant.ofEpochMilli(lastMod));
            entry.title = title;
            entry.summary = summary;
            entry.fileName = fileName;
            entry.filePath = file.toAbsolutePath().toString();
            entry.sizeBytes = size;
            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    public static Map<String, Object> listDiagnosticsSummary() {
        if (recentIncidents.isEmpty()) {
            reloadExistingFilesIntoIndex();
        }
        List<Map<String, Object>> items = recentIncidents.stream()
            .map(DiagnosticIncidentEntry::toMap)
            .collect(Collectors.toList());

        long heapCount = recentIncidents.stream().filter(i -> "HEAP_SPACE".equals(i.category)).count();
        long leakCount = recentIncidents.stream().filter(i -> "CONNECTION_LEAK".equals(i.category)).count();
        long crashCount = recentIncidents.stream().filter(i -> "CRASH".equals(i.category)).count();
        long failureCount = recentIncidents.stream().filter(i -> "FAILURE".equals(i.category)).count();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("diagnosticsDir", diagnosticsDir.toAbsolutePath().toString());
        res.put("totalCount", items.size());
        res.put("heapIssueCount", heapCount);
        res.put("connectionLeakCount", leakCount);
        res.put("crashCount", crashCount);
        res.put("failureCount", failureCount);
        res.put("incidents", items);
        return res;
    }

    public static String readDiagnosticFile(String fileName) throws IOException {
        if (fileName == null || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("Invalid diagnostic log filename");
        }
        Path p = diagnosticsDir.resolve(fileName);
        if (!Files.exists(p)) {
            throw new NoSuchFileException("Diagnostic log not found: " + fileName);
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    public static int clearAllDiagnosticFiles() {
        int deleted = 0;
        ensureDirectory();
        try (Stream<Path> stream = Files.list(diagnosticsDir)) {
            List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".log")).collect(Collectors.toList());
            for (Path p : files) {
                if (Files.deleteIfExists(p)) deleted++;
            }
        } catch (Exception e) {
            log.warn("Error clearing diagnostic files: {}", e.getMessage());
        }
        recentIncidents.clear();
        return deleted;
    }
}
