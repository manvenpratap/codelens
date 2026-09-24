package com.codelens.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Enterprise JVM Monitoring & Control Service.
 * Provides real-time telemetry on Heap/Non-Heap memory pools, Garbage Collection pauses,
 * active threads, CPU metrics, and controls for manual GC, deadlock scans, and thread dumps.
 */
public class JvmManagerService {

    private static final Logger log = LoggerFactory.getLogger(JvmManagerService.class);
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final ClassLoadingMXBean classLoadingBean = ManagementFactory.getClassLoadingMXBean();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private final List<MemoryPoolMXBean> poolBeans = ManagementFactory.getMemoryPoolMXBeans();

    public JvmManagerService() {
        if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
            try {
                threadBean.setThreadCpuTimeEnabled(true);
            } catch (SecurityException | UnsupportedOperationException ignored) {}
        }
    }

    /**
     * Gather complete JVM telemetry metrics for the Process Manager HUD & JVM Manager panel.
     */
    public Map<String, Object> getComprehensiveMetrics() {
        Map<String, Object> root = new LinkedHashMap<>();

        // 1. Heap Memory
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        long heapUsed = heap.getUsed();
        long heapCommitted = heap.getCommitted();
        long heapMax = heap.getMax() > 0 ? heap.getMax() : heapCommitted;
        int heapPct = (heapMax > 0) ? (int) ((heapUsed * 100L) / heapMax) : 0;

        Map<String, Object> heapMap = new LinkedHashMap<>();
        heapMap.put("usedBytes", heapUsed);
        heapMap.put("usedMb", heapUsed / (1024 * 1024));
        heapMap.put("committedBytes", heapCommitted);
        heapMap.put("committedMb", heapCommitted / (1024 * 1024));
        heapMap.put("maxBytes", heapMax);
        heapMap.put("maxMb", heapMax / (1024 * 1024));
        heapMap.put("percentage", heapPct);
        heapMap.put("freeMb", Math.max(0, (heapMax - heapUsed) / (1024 * 1024)));
        root.put("heap", heapMap);

        // 2. Non-Heap Memory
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
        long nonHeapUsed = nonHeap.getUsed();
        long nonHeapCommitted = nonHeap.getCommitted();
        long nonHeapMax = nonHeap.getMax();

        Map<String, Object> nonHeapMap = new LinkedHashMap<>();
        nonHeapMap.put("usedBytes", nonHeapUsed);
        nonHeapMap.put("usedMb", nonHeapUsed / (1024 * 1024));
        nonHeapMap.put("committedMb", nonHeapCommitted / (1024 * 1024));
        nonHeapMap.put("maxMb", nonHeapMax > 0 ? (nonHeapMax / (1024 * 1024)) : -1);
        root.put("nonHeap", nonHeapMap);

        // 3. Memory Pools Breakdown
        List<Map<String, Object>> poolsList = new ArrayList<>();
        for (MemoryPoolMXBean pool : poolBeans) {
            Map<String, Object> pMap = new LinkedHashMap<>();
            pMap.put("name", pool.getName());
            pMap.put("type", pool.getType().toString());
            MemoryUsage u = pool.getUsage();
            long uUsed = u != null ? u.getUsed() : 0;
            long uMax = u != null ? (u.getMax() > 0 ? u.getMax() : u.getCommitted()) : 0;
            pMap.put("usedMb", uUsed / (1024 * 1024));
            pMap.put("maxMb", uMax > 0 ? (uMax / (1024 * 1024)) : -1);
            pMap.put("percentage", (uMax > 0) ? (int) ((uUsed * 100L) / uMax) : -1);
            poolsList.add(pMap);
        }
        root.put("memoryPools", poolsList);

        // 4. Garbage Collection
        long totalGcCount = 0;
        long totalGcTimeMs = 0;
        List<Map<String, Object>> gcList = new ArrayList<>();
        for (GarbageCollectorMXBean gc : gcBeans) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            if (count > 0) totalGcCount += count;
            if (time > 0) totalGcTimeMs += time;

            Map<String, Object> gcMap = new LinkedHashMap<>();
            gcMap.put("name", gc.getName());
            gcMap.put("count", count);
            gcMap.put("timeMs", time);
            gcMap.put("pools", gc.getMemoryPoolNames());
            gcList.add(gcMap);
        }
        Map<String, Object> gcSummary = new LinkedHashMap<>();
        gcSummary.put("totalCollections", totalGcCount);
        gcSummary.put("totalTimeMs", totalGcTimeMs);
        gcSummary.put("collectors", gcList);
        root.put("gc", gcSummary);

        // 5. Threads Matrix
        int liveCount = threadBean.getThreadCount();
        int peakCount = threadBean.getPeakThreadCount();
        int daemonCount = threadBean.getDaemonThreadCount();
        long totalStarted = threadBean.getTotalStartedThreadCount();

        long[] deadlocked = threadBean.findDeadlockedThreads();
        int deadlocksCount = (deadlocked != null) ? deadlocked.length : 0;

        int runnableCount = 0;
        int waitingCount = 0;
        int timedWaitingCount = 0;
        int blockedCount = 0;

        ThreadInfo[] allInfos = threadBean.dumpAllThreads(false, false);
        for (ThreadInfo ti : allInfos) {
            if (ti == null) continue;
            Thread.State st = ti.getThreadState();
            if (st == Thread.State.RUNNABLE) runnableCount++;
            else if (st == Thread.State.WAITING) waitingCount++;
            else if (st == Thread.State.TIMED_WAITING) timedWaitingCount++;
            else if (st == Thread.State.BLOCKED) blockedCount++;
        }

        Map<String, Object> threadSummary = new LinkedHashMap<>();
        threadSummary.put("liveCount", liveCount);
        threadSummary.put("peakCount", peakCount);
        threadSummary.put("daemonCount", daemonCount);
        threadSummary.put("totalStarted", totalStarted);
        threadSummary.put("deadlocksCount", deadlocksCount);
        threadSummary.put("runnable", runnableCount);
        threadSummary.put("waiting", waitingCount);
        threadSummary.put("timedWaiting", timedWaitingCount);
        threadSummary.put("blocked", blockedCount);
        root.put("threads", threadSummary);

        // 6. Runtime & Host Environment
        Map<String, Object> runtimeMap = new LinkedHashMap<>();
        long uptime = runtimeBean.getUptime();
        runtimeMap.put("vmName", runtimeBean.getVmName());
        runtimeMap.put("vmVendor", runtimeBean.getVmVendor());
        runtimeMap.put("vmVersion", runtimeBean.getVmVersion());
        runtimeMap.put("specVersion", runtimeBean.getSpecVersion());
        runtimeMap.put("startTime", runtimeBean.getStartTime());
        runtimeMap.put("uptimeMs", uptime);
        runtimeMap.put("uptimeFormatted", formatDuration(uptime));
        runtimeMap.put("inputArguments", runtimeBean.getInputArguments());
        root.put("runtime", runtimeMap);

        // 7. Operating System & CPU
        Map<String, Object> osMap = new LinkedHashMap<>();
        osMap.put("name", osBean.getName());
        osMap.put("version", osBean.getVersion());
        osMap.put("arch", osBean.getArch());
        osMap.put("availableProcessors", osBean.getAvailableProcessors());
        osMap.put("systemLoadAverage", Math.round(osBean.getSystemLoadAverage() * 100.0) / 100.0);

        // Query extended OS attributes via reflection if available on HotSpot
        extractExtendedOsMetrics(osMap);
        root.put("os", osMap);

        // 8. Class Loading
        Map<String, Object> clMap = new LinkedHashMap<>();
        clMap.put("loadedClassCount", classLoadingBean.getLoadedClassCount());
        clMap.put("totalLoadedClassCount", classLoadingBean.getTotalLoadedClassCount());
        clMap.put("unloadedClassCount", classLoadingBean.getUnloadedClassCount());
        root.put("classLoading", clMap);

        root.put("timestamp", System.currentTimeMillis());
        return root;
    }

    /**
     * Retrieve list of active threads, optionally filtered by keyword and state.
     */
    public List<Map<String, Object>> getThreadList(String query, String stateFilter) {
        ThreadInfo[] infos = threadBean.dumpAllThreads(true, true);
        List<Map<String, Object>> list = new ArrayList<>();
        String q = (query != null && !query.isBlank()) ? query.toLowerCase().trim() : null;
        String sf = (stateFilter != null && !stateFilter.isBlank() && !"ALL".equalsIgnoreCase(stateFilter))
                ? stateFilter.toUpperCase().trim() : null;

        for (ThreadInfo ti : infos) {
            if (ti == null) continue;
            String name = ti.getThreadName();
            String state = ti.getThreadState().toString();

            if (sf != null && !state.equals(sf)) continue;
            if (q != null && !name.toLowerCase().contains(q) && !String.valueOf(ti.getThreadId()).contains(q)) {
                continue;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ti.getThreadId());
            m.put("name", name);
            m.put("state", state);
            m.put("priority", ti.getPriority());
            m.put("daemon", ti.isDaemon());
            m.put("waitedCount", ti.getWaitedCount());
            m.put("blockedCount", ti.getBlockedCount());
            m.put("lockName", ti.getLockName() != null ? ti.getLockName() : "-");
            m.put("lockOwner", ti.getLockOwnerName() != null ? ti.getLockOwnerName() : "-");

            long cpuTimeNs = -1;
            if (threadBean.isThreadCpuTimeSupported()) {
                try {
                    cpuTimeNs = threadBean.getThreadCpuTime(ti.getThreadId());
                } catch (Exception ignored) {}
            }
            m.put("cpuTimeMs", cpuTimeNs > 0 ? (cpuTimeNs / 1_000_000.0) : -1);

            StackTraceElement[] stack = ti.getStackTrace();
            m.put("stackDepth", stack != null ? stack.length : 0);
            if (stack != null && stack.length > 0) {
                m.put("topFrame", stack[0].toString());
            } else {
                m.put("topFrame", "-");
            }

            list.add(m);
        }

        // Sort by state (RUNNABLE first) then by CPU time or name
        list.sort((a, b) -> {
            String sa = (String) a.get("state");
            String sb = (String) b.get("state");
            if (!sa.equals(sb)) {
                if ("RUNNABLE".equals(sa)) return -1;
                if ("RUNNABLE".equals(sb)) return 1;
                if ("BLOCKED".equals(sa)) return -1;
                if ("BLOCKED".equals(sb)) return 1;
            }
            Double ca = (Double) a.get("cpuTimeMs");
            Double cb = (Double) b.get("cpuTimeMs");
            if (ca != null && cb != null && !ca.equals(cb)) {
                return Double.compare(cb, ca);
            }
            return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });

        return list;
    }

    /**
     * Get stack trace details for a single thread.
     */
    public Map<String, Object> getThreadStackTrace(long threadId) {
        ThreadInfo ti = threadBean.getThreadInfo(threadId, Integer.MAX_VALUE);
        if (ti == null) {
            return Map.of("found", false, "message", "Thread #" + threadId + " not found or terminated.");
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("found", true);
        res.put("id", ti.getThreadId());
        res.put("name", ti.getThreadName());
        res.put("state", ti.getThreadState().toString());
        res.put("priority", ti.getPriority());
        res.put("daemon", ti.isDaemon());
        res.put("lockName", ti.getLockName());
        res.put("lockOwnerName", ti.getLockOwnerName());
        res.put("lockOwnerId", ti.getLockOwnerId());

        List<Map<String, Object>> frames = new ArrayList<>();
        StackTraceElement[] st = ti.getStackTrace();
        if (st != null) {
            for (StackTraceElement elem : st) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("className", elem.getClassName());
                f.put("methodName", elem.getMethodName());
                f.put("fileName", elem.getFileName());
                f.put("lineNumber", elem.getLineNumber());
                f.put("isNative", elem.isNativeMethod());
                f.put("isCodeLens", elem.getClassName().startsWith("com.codelens"));
                f.put("text", elem.toString());
                frames.add(f);
            }
        }
        res.put("frames", frames);
        return res;
    }

    /**
     * Generate full text thread dump for export or inspection.
     */
    public Map<String, Object> generateThreadDump() {
        ThreadInfo[] infos = threadBean.dumpAllThreads(true, true);
        StringBuilder sb = new StringBuilder();
        String timestamp = ISO_FMT.format(Instant.now());

        sb.append(timestamp).append("\n");
        sb.append("Full thread dump ").append(runtimeBean.getVmName())
                .append(" (").append(runtimeBean.getVmVersion()).append("):\n\n");

        for (ThreadInfo ti : infos) {
            if (ti == null) continue;
            sb.append("\"").append(ti.getThreadName()).append("\" #")
                    .append(ti.getThreadId())
                    .append(ti.isDaemon() ? " daemon" : "")
                    .append(" prio=").append(ti.getPriority());

            if (threadBean.isThreadCpuTimeSupported()) {
                long cpuNs = threadBean.getThreadCpuTime(ti.getThreadId());
                if (cpuNs > 0) {
                    sb.append(" cpu=").append(String.format("%.2fms", cpuNs / 1_000_000.0));
                }
            }
            sb.append(" state=").append(ti.getThreadState()).append("\n");
            sb.append("   java.lang.Thread.State: ").append(ti.getThreadState());
            if (ti.getLockName() != null) {
                sb.append(" (waiting on ").append(ti.getLockName());
                if (ti.getLockOwnerName() != null) {
                    sb.append(" owned by \"").append(ti.getLockOwnerName()).append("\" #").append(ti.getLockOwnerId());
                }
                sb.append(")");
            }
            sb.append("\n");

            StackTraceElement[] stack = ti.getStackTrace();
            if (stack != null) {
                for (StackTraceElement elem : stack) {
                    sb.append("\tat ").append(elem.toString()).append("\n");
                }
            }

            MonitorInfo[] monitors = ti.getLockedMonitors();
            if (monitors != null && monitors.length > 0) {
                sb.append("   Locked monitors:\n");
                for (MonitorInfo mi : monitors) {
                    sb.append("\t- ").append(mi.getClassName()).append("@")
                            .append(Integer.toHexString(mi.getIdentityHashCode()))
                            .append(" at depth ").append(mi.getLockedStackDepth()).append("\n");
                }
            }

            LockInfo[] locks = ti.getLockedSynchronizers();
            if (locks != null && locks.length > 0) {
                sb.append("   Locked synchronizers:\n");
                for (LockInfo li : locks) {
                    sb.append("\t- <").append(li.getClassName()).append("@")
                            .append(Integer.toHexString(li.getIdentityHashCode())).append(">\n");
                }
            }
            sb.append("\n");
        }

        // Deadlocks check section
        long[] deadlocked = threadBean.findDeadlockedThreads();
        if (deadlocked != null && deadlocked.length > 0) {
            sb.append("Found ").append(deadlocked.length).append(" deadlocked thread(s)!\n");
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", timestamp);
        resp.put("threadCount", infos.length);
        resp.put("deadlockCount", deadlocked != null ? deadlocked.length : 0);
        resp.put("rawText", sb.toString());
        return resp;
    }

    /**
     * Scan and report any deadlocks.
     */
    public Map<String, Object> findDeadlocks() {
        long[] ids = threadBean.findDeadlockedThreads();
        if (ids == null || ids.length == 0) {
            return Map.of("hasDeadlocks", false, "count", 0, "message", "No deadlocked threads detected.");
        }

        ThreadInfo[] infos = threadBean.getThreadInfo(ids, true, true);
        List<Map<String, Object>> deadlockedList = new ArrayList<>();
        for (ThreadInfo ti : infos) {
            if (ti == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ti.getThreadId());
            m.put("name", ti.getThreadName());
            m.put("state", ti.getThreadState().toString());
            m.put("lockName", ti.getLockName());
            m.put("lockOwner", ti.getLockOwnerName());
            m.put("lockOwnerId", ti.getLockOwnerId());
            deadlockedList.add(m);
        }

        return Map.of(
                "hasDeadlocks", true,
                "count", ids.length,
                "threads", deadlockedList,
                "message", String.format("Found %d deadlocked thread(s)!", ids.length)
        );
    }

    /**
     * Trigger explicit garbage collection and calculate memory freed.
     */
    public Map<String, Object> triggerGc() {
        MemoryUsage before = memoryBean.getHeapMemoryUsage();
        long beforeUsed = before.getUsed();
        long t0 = System.currentTimeMillis();

        System.gc();

        long durationMs = System.currentTimeMillis() - t0;
        MemoryUsage after = memoryBean.getHeapMemoryUsage();
        long afterUsed = after.getUsed();
        long freedBytes = Math.max(0, beforeUsed - afterUsed);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("beforeUsedMb", beforeUsed / (1024 * 1024));
        res.put("afterUsedMb", afterUsed / (1024 * 1024));
        res.put("freedMb", Math.round((freedBytes / (1024.0 * 1024.0)) * 10.0) / 10.0);
        res.put("freedBytes", freedBytes);
        res.put("durationMs", durationMs);
        res.put("timestamp", System.currentTimeMillis());

        log.info("Explicit GC triggered via JVM Manager: reclaimed {} MB in {} ms (heap: {} MB -> {} MB)",
                res.get("freedMb"), durationMs, res.get("beforeUsedMb"), res.get("afterUsedMb"));
        return res;
    }

    /**
     * Clear caches and trigger memory trim.
     */
    public Map<String, Object> trimMemory(Runnable cacheClearer) {
        long t0 = System.currentTimeMillis();
        MemoryUsage before = memoryBean.getHeapMemoryUsage();
        long beforeUsed = before.getUsed();

        if (cacheClearer != null) {
            try {
                cacheClearer.run();
            } catch (Exception e) {
                log.warn("Cache clearer hook threw exception: {}", e.getMessage());
            }
        }

        System.gc();

        long durationMs = System.currentTimeMillis() - t0;
        MemoryUsage after = memoryBean.getHeapMemoryUsage();
        long afterUsed = after.getUsed();
        long freedBytes = Math.max(0, beforeUsed - afterUsed);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("beforeUsedMb", beforeUsed / (1024 * 1024));
        res.put("afterUsedMb", afterUsed / (1024 * 1024));
        res.put("freedMb", Math.round((freedBytes / (1024.0 * 1024.0)) * 10.0) / 10.0);
        res.put("freedBytes", freedBytes);
        res.put("durationMs", durationMs);
        res.put("message", String.format("Trimmed caches & executed GC: freed %.1f MB in %d ms",
                (freedBytes / (1024.0 * 1024.0)), durationMs));
        return res;
    }

    private void extractExtendedOsMetrics(Map<String, Object> map) {
        try {
            Class<?> sunOsClass = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (sunOsClass.isInstance(osBean)) {
                invokeDoubleMethod(sunOsClass, "getProcessCpuLoad", map, "processCpuLoad");
                invokeDoubleMethod(sunOsClass, "getCpuLoad", map, "systemCpuLoad");
                invokeLongMethod(sunOsClass, "getTotalMemorySize", map, "totalPhysicalMemoryBytes");
                invokeLongMethod(sunOsClass, "getFreeMemorySize", map, "freePhysicalMemoryBytes");
                invokeLongMethod(sunOsClass, "getCommittedVirtualMemorySize", map, "committedVirtualMemoryBytes");

                Long totMem = (Long) map.get("totalPhysicalMemoryBytes");
                Long freeMem = (Long) map.get("freePhysicalMemoryBytes");
                if (totMem != null && totMem > 0) {
                    map.put("totalPhysicalMemMb", totMem / (1024 * 1024));
                }
                if (freeMem != null && freeMem >= 0) {
                    map.put("freePhysicalMemMb", freeMem / (1024 * 1024));
                }

                Double pCpu = (Double) map.get("processCpuLoad");
                if (pCpu != null && pCpu >= 0) {
                    map.put("processCpuPercent", Math.round(pCpu * 100.0 * 10.0) / 10.0);
                }
                Double sCpu = (Double) map.get("systemCpuLoad");
                if (sCpu != null && sCpu >= 0) {
                    map.put("systemCpuPercent", Math.round(sCpu * 100.0 * 10.0) / 10.0);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void invokeDoubleMethod(Class<?> clazz, String name, Map<String, Object> map, String key) {
        try {
            java.lang.reflect.Method m = clazz.getMethod(name);
            Object v = m.invoke(osBean);
            if (v instanceof Number n) {
                map.put(key, n.doubleValue());
            }
        } catch (Throwable ignored) {}
    }

    private void invokeLongMethod(Class<?> clazz, String name, Map<String, Object> map, String key) {
        try {
            java.lang.reflect.Method m = clazz.getMethod(name);
            Object v = m.invoke(osBean);
            if (v instanceof Number n) {
                map.put(key, n.longValue());
            }
        } catch (Throwable ignored) {}
    }

    private static String formatDuration(long ms) {
        long sec = (ms / 1000) % 60;
        long min = (ms / (1000 * 60)) % 60;
        long hours = (ms / (1000 * 60 * 60)) % 24;
        long days = ms / (1000 * 60 * 60 * 24);
        if (days > 0) return String.format("%dd %dh %dm", days, hours, min);
        if (hours > 0) return String.format("%dh %dm %ds", hours, min, sec);
        if (min > 0) return String.format("%dm %ds", min, sec);
        return String.format("%ds", sec);
    }
}
