package com.codelens.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import java.lang.management.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enterprise Java Heap Auto-Recovery & Memory Watchdog.
 *
 * Provides a resilient multi-ring defense against java.lang.OutOfMemoryError (Java heap space):
 * 1. Proactive Heap Watchdog (polls every 3s + JMX MemoryPool threshold notifications).
 * 2. Multi-tier Thresholds: Warning (75%), Critical (85%), Emergency (92%).
 * 3. Coordinated Recovery Pipeline:
 *    - In-memory graph layout cache eviction (disk cache persists, zero data loss)
 *    - Module dependency insight cache eviction
 *    - In-memory database buffer & page cache shrink (SET CACHE_SIZE 16MB + CHECKPOINT)
 *    - Synchronous Garbage Collection (System.gc())
 * 4. Memory Circuit Breaker:
 *    - Trips OPEN if heap remains >92% after recovery, rejecting new heavy graph / scan / stress requests
 *      with HTTP 503 instead of crashing the JVM.
 *    - Automatically resets to CLOSED once heap drops below 75% (hysteresis).
 * 5. Global OutOfMemoryError Trap:
 *    - Intercepts trapped OOM errors in HTTP handlers and background tasks, immediately reclaiming memory
 *      and returning clean diagnostic responses.
 * 6. Incident Audit History:
 *    - In-memory ring buffer recording the last 50 auto-recovery events with telemetry and actions.
 */
public class HeapAutoRecoveryManager {

    private static final Logger log = LoggerFactory.getLogger(HeapAutoRecoveryManager.class);
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final List<MemoryPoolMXBean> poolBeans = ManagementFactory.getMemoryPoolMXBeans();

    // Thresholds
    private volatile int warningThresholdPct = 75;
    private volatile int criticalThresholdPct = 85;
    private volatile int emergencyThresholdPct = 92;
    private volatile int hysteresisResetPct = 75;

    // State & Metrics
    private final AtomicBoolean watchdogRunning = new AtomicBoolean(false);
    private final AtomicBoolean circuitBreakerActive = new AtomicBoolean(false);
    private final AtomicLong totalRecoveriesCount = new AtomicLong(0);
    private final AtomicLong totalReclaimedBytes = new AtomicLong(0);
    private final AtomicLong lastRecoveryTimestamp = new AtomicLong(0);
    private final AtomicLong lastRecoveryDurationMs = new AtomicLong(0);
    private final AtomicReference<Double> lastRecoveryFreedMb = new AtomicReference<>(0.0);
    private final AtomicReference<String> lastRecoveryTrigger = new AtomicReference<>("None");
    private final AtomicLong lastAutoRecoveryAttempt = new AtomicLong(0);

    // Minimum cooldown (in millis) between automatic watchdog passes (to prevent thrashing)
    private static final long AUTO_RECOVERY_COOLDOWN_MS = 4000;

    // Incident History (bounded to last 50 incidents)
    private final Deque<AutoRecoveryIncident> incidentHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_INCIDENTS = 50;

    // Registered Recovery Hooks
    private final List<RecoveryHook> recoveryHooks = new CopyOnWriteArrayList<>();
    private Runnable onCircuitBreakerResetHook;

    // Background Watchdog Executor
    private ScheduledExecutorService watchdogExecutor;

    public interface RecoveryHook {
        String getName();
        void execute(List<String> actionsLog) throws Exception;
    }

    public static class AutoRecoveryIncident {
        public final String id;
        public final long timestamp;
        public final String timestampFormatted;
        public final String trigger;
        public final long heapBeforeMb;
        public final long heapMaxMb;
        public final long heapAfterMb;
        public final double reclaimedMb;
        public final int percentageBefore;
        public final int percentageAfter;
        public final long durationMs;
        public final boolean circuitBreakerActive;
        public final List<String> actions;

        public AutoRecoveryIncident(String id, long timestamp, String trigger,
                                    long heapBeforeMb, long heapMaxMb, long heapAfterMb,
                                    double reclaimedMb, int percentageBefore, int percentageAfter,
                                    long durationMs, boolean circuitBreakerActive, List<String> actions) {
            this.id = id;
            this.timestamp = timestamp;
            this.timestampFormatted = ISO_FMT.format(Instant.ofEpochMilli(timestamp));
            this.trigger = trigger;
            this.heapBeforeMb = heapBeforeMb;
            this.heapMaxMb = heapMaxMb;
            this.heapAfterMb = heapAfterMb;
            this.reclaimedMb = reclaimedMb;
            this.percentageBefore = percentageBefore;
            this.percentageAfter = percentageAfter;
            this.durationMs = durationMs;
            this.circuitBreakerActive = circuitBreakerActive;
            this.actions = actions != null ? new ArrayList<>(actions) : Collections.emptyList();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("timestamp", timestamp);
            map.put("timestampFormatted", timestampFormatted);
            map.put("trigger", trigger);
            map.put("heapBeforeMb", heapBeforeMb);
            map.put("heapMaxMb", heapMaxMb);
            map.put("heapAfterMb", heapAfterMb);
            map.put("reclaimedMb", reclaimedMb);
            map.put("percentageBefore", percentageBefore);
            map.put("percentageAfter", percentageAfter);
            map.put("durationMs", durationMs);
            map.put("circuitBreakerActive", circuitBreakerActive);
            map.put("actions", actions);
            return map;
        }
    }

    public HeapAutoRecoveryManager() {
        initJmxThresholds();
    }

    /**
     * Register a recovery hook to be executed when memory pressure is detected.
     */
    public void registerRecoveryHook(String name, Runnable hook) {
        recoveryHooks.add(new RecoveryHook() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public void execute(List<String> actionsLog) throws Exception {
                hook.run();
                actionsLog.add("Executed: " + name);
            }
        });
    }

    /**
     * Register hook for when circuit breaker resets back to closed.
     */
    public void setOnCircuitBreakerReset(Runnable hook) {
        this.onCircuitBreakerResetHook = hook;
    }

    /**
     * Arm JMX memory usage threshold notifications on the Tenured/Old Gen heap memory pool.
     */
    private void initJmxThresholds() {
        try {
            for (MemoryPoolMXBean pool : poolBeans) {
                if (pool.getType() == MemoryType.HEAP && pool.isUsageThresholdSupported()) {
                    long max = pool.getUsage().getMax();
                    if (max > 0) {
                        long threshold = (long) (max * (criticalThresholdPct / 100.0));
                        pool.setUsageThreshold(threshold);
                        log.info("Armed JMX memory usage threshold on pool '{}' at {} MB ({}%)",
                                pool.getName(), threshold / (1024 * 1024), criticalThresholdPct);
                    }
                }
            }

            if (memoryBean instanceof NotificationEmitter) {
                NotificationEmitter emitter = (NotificationEmitter) memoryBean;
                emitter.addNotificationListener(new NotificationListener() {
                    @Override
                    public void handleNotification(Notification notification, Object handback) {
                        String type = notification.getType();
                        if ("java.management.memory.threshold.exceeded".equals(type) ||
                            "java.management.memory.collection.threshold.exceeded".equals(type)) {
                            log.warn("JMX Memory Notification received: {}. Triggering auto-recovery...", type);
                            triggerAutoRecovery("JMX_" + type);
                        }
                    }
                }, null, null);
                log.info("JMX Memory Notification Listener armed successfully.");
            }
        } catch (Exception e) {
            log.warn("Could not arm JMX memory pool threshold listeners: {}", e.getMessage());
        }
    }

    /**
     * Start the continuous background watchdog polling thread.
     */
    public synchronized void startWatchdog() {
        if (watchdogRunning.get()) return;

        watchdogExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CodeLens-HeapWatchdog");
            t.setDaemon(true);
            return t;
        });

        watchdogExecutor.scheduleWithFixedDelay(this::checkHeapPressure, 3, 3, TimeUnit.SECONDS);
        watchdogRunning.set(true);
        log.info("Heap Memory Auto-Recovery Watchdog started (polling every 3s, warning: {}%, critical: {}%, emergency: {}%)",
                warningThresholdPct, criticalThresholdPct, emergencyThresholdPct);
    }

    /**
     * Stop the background watchdog.
     */
    public synchronized void stopWatchdog() {
        if (!watchdogRunning.get()) return;
        watchdogRunning.set(false);
        if (watchdogExecutor != null) {
            watchdogExecutor.shutdownNow();
            watchdogExecutor = null;
        }
        log.info("Heap Memory Auto-Recovery Watchdog stopped.");
    }

    /**
     * Periodic watchdog check.
     */
    private void checkHeapPressure() {
        try {
            MemoryUsage heap = memoryBean.getHeapMemoryUsage();
            long used = heap.getUsed();
            long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
            int pct = (max > 0) ? (int) ((used * 100L) / max) : 0;

            // 1. Emergency Threshold Check (>92%)
            if (pct >= emergencyThresholdPct) {
                log.error("CRITICAL HEAP PRESSURE: {}% used ({} MB / {} MB) >= emergency threshold {}%!",
                        pct, used / (1024 * 1024), max / (1024 * 1024), emergencyThresholdPct);
                circuitBreakerActive.set(true);
                executeAutoRecovery("WATCHDOG_EMERGENCY (" + pct + "% Heap)");
                return;
            }

            // 2. Critical Threshold Check (>85%)
            if (pct >= criticalThresholdPct) {
                long now = System.currentTimeMillis();
                if (now - lastAutoRecoveryAttempt.get() >= AUTO_RECOVERY_COOLDOWN_MS) {
                    log.warn("High heap pressure detected: {}% used ({} MB / {} MB) >= critical threshold {}%. Auto-recovering...",
                            pct, used / (1024 * 1024), max / (1024 * 1024), criticalThresholdPct);
                    executeAutoRecovery("WATCHDOG_CRITICAL (" + pct + "% Heap)");
                }
                return;
            }

            // 3. Warning Threshold Check (>75%)
            if (pct >= warningThresholdPct) {
                log.debug("Heap usage in warning zone: {}% ({} MB / {} MB)", pct, used / (1024 * 1024), max / (1024 * 1024));
            }

            // 4. Circuit Breaker Hysteresis Reset (<75%)
            if (circuitBreakerActive.get() && pct < hysteresisResetPct) {
                log.info("Heap memory recovered to safe level: {}% (< {}% reset threshold). Resetting circuit breaker to CLOSED.",
                        pct, hysteresisResetPct);
                circuitBreakerActive.set(false);
                if (onCircuitBreakerResetHook != null) {
                    try {
                        onCircuitBreakerResetHook.run();
                    } catch (Exception e) {
                        log.warn("Circuit breaker reset hook exception: {}", e.getMessage());
                    }
                }
            }

        } catch (Throwable t) {
            log.error("Error during heap watchdog check: {}", t.getMessage());
        }
    }

    /**
     * Manually or programmatically trigger an auto-recovery pass.
     */
    public AutoRecoveryIncident triggerAutoRecovery(String triggerSource) {
        return executeAutoRecovery(triggerSource);
    }

    /**
     * Emergency Trap: Handle an intercepted OutOfMemoryError from an HTTP handler or background worker.
     */
    public AutoRecoveryIncident handleTrappedOOM(String source, Throwable error) {
        log.error("╔════════════════════════════════════════════════════════════════════════════════╗");
        log.error("║ EMERGENCY: OutOfMemoryError trapped from [{}]!                                 ║", source);
        log.error("║ Initiating immediate emergency heap auto-recovery pipeline...                  ║");
        log.error("╚════════════════════════════════════════════════════════════════════════════════╝");

        // Force circuit breaker on trapped OOM
        circuitBreakerActive.set(true);

        // Execute recovery immediately (bypasses cooldown)
        return executeAutoRecovery("TRAPPED_OOM: " + source);
    }

    /**
     * Core Auto-Recovery Execution Pipeline.
     */
    private synchronized AutoRecoveryIncident executeAutoRecovery(String trigger) {
        long t0 = System.currentTimeMillis();
        lastAutoRecoveryAttempt.set(t0);

        MemoryUsage before = memoryBean.getHeapMemoryUsage();
        long beforeUsed = before.getUsed();
        long beforeMax = before.getMax() > 0 ? before.getMax() : before.getCommitted();
        int beforePct = (beforeMax > 0) ? (int) ((beforeUsed * 100L) / beforeMax) : 0;

        List<String> actions = new ArrayList<>();

        // Step 1: Execute all registered recovery hooks
        for (RecoveryHook hook : recoveryHooks) {
            try {
                hook.execute(actions);
            } catch (Throwable e) {
                actions.add("Hook error [" + hook.getName() + "]: " + e.getMessage());
                log.warn("Recovery hook '{}' threw error: {}", hook.getName(), e.getMessage());
            }
        }

        // Step 2: Invoke Garbage Collection
        long gcT0 = System.currentTimeMillis();
        System.gc();
        long gcDuration = System.currentTimeMillis() - gcT0;
        actions.add(String.format("Executed System.gc() in %d ms", gcDuration));

        // Step 3: Measure post-recovery heap state
        MemoryUsage after = memoryBean.getHeapMemoryUsage();
        long afterUsed = after.getUsed();
        long afterMax = after.getMax() > 0 ? after.getMax() : after.getCommitted();
        int afterPct = (afterMax > 0) ? (int) ((afterUsed * 100L) / afterMax) : 0;
        long durationMs = System.currentTimeMillis() - t0;

        long freedBytes = Math.max(0, beforeUsed - afterUsed);
        double freedMb = Math.round((freedBytes / (1024.0 * 1024.0)) * 10.0) / 10.0;

        // Step 4: Circuit breaker evaluation
        boolean cbActive = circuitBreakerActive.get();
        if (afterPct >= emergencyThresholdPct) {
            circuitBreakerActive.set(true);
            cbActive = true;
            actions.add(String.format("Memory pressure still critical (%d%%). Circuit breaker kept OPEN.", afterPct));
        } else if (cbActive && afterPct < hysteresisResetPct) {
            circuitBreakerActive.set(false);
            cbActive = false;
            actions.add(String.format("Heap stabilized below %d%% (%d%%). Circuit breaker reset to CLOSED.", hysteresisResetPct, afterPct));
            if (onCircuitBreakerResetHook != null) {
                try { onCircuitBreakerResetHook.run(); } catch (Exception ignored) {}
            }
        }

        // Step 5: Update Telemetry & History
        totalRecoveriesCount.incrementAndGet();
        totalReclaimedBytes.addAndGet(freedBytes);
        lastRecoveryTimestamp.set(t0);
        lastRecoveryDurationMs.set(durationMs);
        lastRecoveryFreedMb.set(freedMb);
        lastRecoveryTrigger.set(trigger);

        String incidentId = "INC-" + System.currentTimeMillis() % 100000;
        AutoRecoveryIncident incident = new AutoRecoveryIncident(
                incidentId, t0, trigger,
                beforeUsed / (1024 * 1024), beforeMax / (1024 * 1024),
                afterUsed / (1024 * 1024), freedMb,
                beforePct, afterPct, durationMs, cbActive, actions
        );

        incidentHistory.addFirst(incident);
        while (incidentHistory.size() > MAX_INCIDENTS) {
            incidentHistory.removeLast();
        }

        log.info("════════════════════════════════════════════════════════════════════════════════");
        log.info("HEAP AUTO-RECOVERY COMPLETE [{}]", incidentId);
        log.info("Trigger: {} | Duration: {} ms", trigger, durationMs);
        log.info("Heap Before: {} MB ({}%) -> After: {} MB ({}%)",
                incident.heapBeforeMb, beforePct, incident.heapAfterMb, afterPct);
        log.info("Reclaimed Memory: {} MB | Circuit Breaker: {}",
                freedMb, cbActive ? "OPEN (Throttling)" : "CLOSED (Normal)");
        log.info("════════════════════════════════════════════════════════════════════════════════");

        return incident;
    }

    /**
     * Safely simulate a transient heap pressure spike to test auto-recovery end-to-end.
     */
    public Map<String, Object> simulateMemoryPressure(int targetMb) {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        long used = heap.getUsed();
        long free = max - used;

        // Allocate up to targetMb or 60% of free heap (whichever is smaller) to ensure total safety
        int safeMb = (int) Math.min(targetMb, Math.max(10, (free * 0.60) / (1024 * 1024)));
        List<byte[]> dummyPayload = new ArrayList<>();

        try {
            int chunks = Math.max(1, safeMb / 5);
            for (int i = 0; i < chunks; i++) {
                dummyPayload.add(new byte[5 * 1024 * 1024]); // 5 MB chunks
            }
        } catch (OutOfMemoryError oom) {
            log.warn("Simulation capped at {} MB due to heap ceiling", dummyPayload.size() * 5);
        }

        // Execute recovery pass while simulated payload is present
        AutoRecoveryIncident incident = executeAutoRecovery("MANUAL_TEST_SIMULATION (" + safeMb + " MB payload)");

        // Release simulated dummy payload and collect
        dummyPayload.clear();
        System.gc();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("simulatedPayloadMb", safeMb);
        res.put("incident", incident.toMap());
        res.put("telemetry", getStatusAndMetrics());
        return res;
    }

    /**
     * Return comprehensive status and metrics for UI and API consumption.
     */
    public Map<String, Object> getStatusAndMetrics() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        long used = heap.getUsed();
        long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        int pct = (max > 0) ? (int) ((used * 100L) / max) : 0;

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("watchdogActive", watchdogRunning.get());
        map.put("circuitBreakerActive", circuitBreakerActive.get());
        map.put("warningThresholdPct", warningThresholdPct);
        map.put("criticalThresholdPct", criticalThresholdPct);
        map.put("emergencyThresholdPct", emergencyThresholdPct);
        map.put("hysteresisResetPct", hysteresisResetPct);

        map.put("currentHeapPct", pct);
        map.put("currentHeapUsedMb", used / (1024 * 1024));
        map.put("currentHeapMaxMb", max / (1024 * 1024));
        map.put("currentHeapFreeMb", Math.max(0, (max - used) / (1024 * 1024)));

        map.put("totalRecoveries", totalRecoveriesCount.get());
        double totalMb = Math.round((totalReclaimedBytes.get() / (1024.0 * 1024.0)) * 10.0) / 10.0;
        map.put("totalReclaimedMb", totalMb);

        long lastTs = lastRecoveryTimestamp.get();
        map.put("lastRecoveryTimestamp", lastTs);
        map.put("lastRecoveryFormatted", lastTs > 0 ? ISO_FMT.format(Instant.ofEpochMilli(lastTs)) : "None");
        map.put("lastRecoveryFreedMb", lastRecoveryFreedMb.get());
        map.put("lastRecoveryDurationMs", lastRecoveryDurationMs.get());
        map.put("lastRecoveryTrigger", lastRecoveryTrigger.get());

        List<Map<String, Object>> incidentList = new ArrayList<>();
        for (AutoRecoveryIncident inc : incidentHistory) {
            incidentList.add(inc.toMap());
        }
        map.put("incidents", incidentList);

        return map;
    }

    public boolean isCircuitBreakerActive() {
        return circuitBreakerActive.get();
    }

    public void tripCircuitBreaker(String reason) {
        circuitBreakerActive.set(true);
        log.warn("Memory circuit breaker tripped OPEN: {}", reason);
    }

    public void resetCircuitBreaker() {
        circuitBreakerActive.set(false);
        if (onCircuitBreakerResetHook != null) {
            try { onCircuitBreakerResetHook.run(); } catch (Exception ignored) {}
        }
        log.info("Circuit breaker manually reset to CLOSED.");
    }

    public int getCurrentHeapUsagePercentage() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        long used = heap.getUsed();
        long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        return (max > 0) ? (int) ((used * 100L) / max) : 0;
    }

    public void configureThresholds(int warning, int critical, int emergency) {
        if (warning > 0 && warning < critical && critical < emergency && emergency <= 100) {
            this.warningThresholdPct = warning;
            this.criticalThresholdPct = critical;
            this.emergencyThresholdPct = emergency;
            this.hysteresisResetPct = warning;
            log.info("Updated auto-recovery thresholds: warning={}%, critical={}%, emergency={}%", warning, critical, emergency);
        }
    }
}
