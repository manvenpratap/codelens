package com.codelens.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * BackgroundTaskOrchestrator
 *
 * Centralized queue, dependency resolution, concurrency limiter, and load capacity
 * orchestrator for all CodeLens background operations:
 *  - Source Code Scanner (full & incremental)
 *  - Scale & Stress Test Engine
 *  - Database Compaction & Maintenance
 *  - Call Graph & Field Impact Rebuilding
 *  - Sunflower Layout Precomputation
 *  - Module Dependency & Touchpoint Engine
 *  - Lucene Inverted Indexer
 *  - Git Churn & Blame Analyzer
 *  - Intelligence Reports Generator
 *  - Database & Heap Watchdogs
 *
 * Core Guarantees:
 * 1. Concurrency Limiter:
 *    - At most ONE heavy batch task runs at any time (MAX_HEAVY_CONCURRENT = 1).
 *    - Max total load units = 10 (MAX_LOAD_UNITS = 10).
 *    - Heavy = 10 units, Medium = 4 units, Light = 1 unit, Sentinel = 0 units.
 * 2. Mutex Exclusion:
 *    - Tasks in DATABASE_EXCLUSIVE (scanner, delta-scanner, stress-test, db-maintenance)
 *      never run concurrently.
 *    - All database-reading tasks wait if a DATABASE_EXCLUSIVE task is wiping or writing.
 * 3. Dependency Resolution:
 *    - Tasks with declared dependencies (e.g. layout-engine -> call-graph -> scanner)
 *      automatically transition to WAITING_DEPENDENCY until prerequisites finish.
 * 4. Memory Sentinel Throttling:
 *    - If JVM heap >= 85% or HeapAutoRecoveryManager circuit breaker is active,
 *      heavy/medium tasks are THROTTLED and auto-recovery is invoked.
 *    - Once memory drops < 80%, tasks automatically resume.
 * 5. Request Coalescing / Deduplication:
 *    - Redundant clicks/triggers coalesce into pending jobs without duplicating work.
 */
public class BackgroundTaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskOrchestrator.class);

    public static final int MAX_LOAD_UNITS = 10;
    public static final int MAX_HEAVY_CONCURRENT = 1;
    public static final int MEMORY_CRITICAL_HEAP_PCT = 85;
    public static final int MEMORY_RESUME_HEAP_PCT = 80;

    public enum LoadTier {
        HEAVY(10),
        MEDIUM(4),
        LIGHT(1),
        SENTINEL(0);

        private final int defaultUnits;
        LoadTier(int units) { this.defaultUnits = units; }
        public int getDefaultUnits() { return defaultUnits; }
    }

    public enum MutexGroup {
        NONE,
        DATABASE_EXCLUSIVE,
        ANALYSIS_EXCLUSIVE
    }

    public enum Priority {
        URGENT(100),
        HIGH(75),
        NORMAL(50),
        LOW(25);

        private final int level;
        Priority(int level) { this.level = level; }
        public int getLevel() { return level; }
    }

    public enum TaskStatus {
        IDLE,
        QUEUED,
        WAITING_DEPENDENCY,
        THROTTLED,
        RUNNING,
        COMPLETE,
        ERROR,
        CANCELLED
    }

    /**
     * Provider interface for memory pressure evaluation (swappable for unit tests).
     */
    public interface MemoryAdvisor {
        int getHeapUsagePercent();
        boolean isCircuitBreakerActive();
        void triggerRecovery(String reason);
    }

    /**
     * Definition template for a registered background task.
     */
    public static class TaskDefinition {
        public final String id;
        public final String name;
        public final LoadTier tier;
        public final int loadUnits;
        public final MutexGroup mutexGroup;
        public final Priority defaultPriority;
        public final Set<String> dependencies;
        public final BooleanSupplier isReadySupplier;

        public TaskDefinition(String id, String name, LoadTier tier, int loadUnits,
                              MutexGroup mutexGroup, Priority defaultPriority,
                              Set<String> dependencies, BooleanSupplier isReadySupplier) {
            this.id = id;
            this.name = name;
            this.tier = tier;
            this.loadUnits = loadUnits;
            this.mutexGroup = mutexGroup;
            this.defaultPriority = defaultPriority;
            this.dependencies = dependencies != null ? new LinkedHashSet<>(dependencies) : Collections.emptySet();
            this.isReadySupplier = isReadySupplier;
        }
    }

    /**
     * An enqueued or active task execution unit.
     */
    public static class QueuedTask {
        public final String taskId;
        public final String taskName;
        public final LoadTier loadTier;
        public final int loadUnits;
        public final MutexGroup mutexGroup;
        public volatile Priority priority;
        public final Set<String> dependencies;
        public final Runnable action;
        public final long submitTimestamp;

        public volatile TaskStatus status = TaskStatus.QUEUED;
        public final Set<String> waitingFor = Collections.synchronizedSet(new LinkedHashSet<>());
        public volatile String throttleReason = null;
        public volatile Thread runningThread = null;
        public volatile Future<?> future = null;
        public volatile long startTime = 0;
        public volatile long endTime = 0;
        public volatile long durationMs = 0;
        public volatile String errorDetail = null;
        public final AtomicBoolean cancelRequested = new AtomicBoolean(false);

        public QueuedTask(String taskId, String taskName, LoadTier loadTier, int loadUnits,
                          MutexGroup mutexGroup, Priority priority, Set<String> dependencies,
                          Runnable action) {
            this.taskId = taskId;
            this.taskName = taskName;
            this.loadTier = loadTier;
            this.loadUnits = loadUnits;
            this.mutexGroup = mutexGroup;
            this.priority = priority;
            this.dependencies = dependencies != null ? new LinkedHashSet<>(dependencies) : Collections.emptySet();
            this.action = action;
            this.submitTimestamp = System.currentTimeMillis();
        }
    }

    /**
     * Historical state and last known result of a background task.
     */
    public static class TaskState {
        public final String taskId;
        public volatile TaskStatus lastStatus = TaskStatus.IDLE;
        public volatile long lastStartTime = 0;
        public volatile long lastEndTime = 0;
        public volatile long lastDurationMs = 0;
        public volatile String lastErrorDetail = null;

        public TaskState(String taskId) {
            this.taskId = taskId;
        }
    }

    /**
     * Snapshot representation of a task for API responses.
     */
    public static class TaskSnapshot {
        public final String id;
        public final String name;
        public final String loadTier;
        public final int loadUnits;
        public final String mutexGroup;
        public final TaskStatus status;
        public final int queuePosition;
        public final List<String> waitingFor;
        public final String throttleReason;
        public final long startTime;
        public final long endTime;
        public final long durationMs;
        public final String errorDetail;

        public TaskSnapshot(String id, String name, String loadTier, int loadUnits,
                            String mutexGroup, TaskStatus status, int queuePosition,
                            List<String> waitingFor, String throttleReason,
                            long startTime, long endTime, long durationMs, String errorDetail) {
            this.id = id;
            this.name = name;
            this.loadTier = loadTier;
            this.loadUnits = loadUnits;
            this.mutexGroup = mutexGroup;
            this.status = status;
            this.queuePosition = queuePosition;
            this.waitingFor = waitingFor != null ? new ArrayList<>(waitingFor) : Collections.emptyList();
            this.throttleReason = throttleReason;
            this.startTime = startTime;
            this.endTime = endTime;
            this.durationMs = durationMs;
            this.errorDetail = errorDetail;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("loadTier", loadTier);
            m.put("loadUnits", loadUnits);
            m.put("mutexGroup", mutexGroup);
            m.put("status", status.name());
            m.put("queuePosition", queuePosition);
            m.put("waitingFor", waitingFor);
            if (throttleReason != null) m.put("throttleReason", throttleReason);
            m.put("startTime", startTime);
            m.put("endTime", endTime);
            m.put("durationMs", durationMs);
            if (errorDetail != null) m.put("errorDetail", errorDetail);
            return m;
        }
    }

    // Task Registry & State
    private final Map<String, TaskDefinition> registry = new ConcurrentHashMap<>();
    private final Map<String, TaskState> taskStates = new ConcurrentHashMap<>();

    // Queue & Active Tasks
    private final List<QueuedTask> queue = new ArrayList<>();
    private final Map<String, QueuedTask> activeRunningTasks = new ConcurrentHashMap<>();
    private final Set<MutexGroup> activeMutexes = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Capacity Counters
    private final AtomicInteger activeLoadUnits = new AtomicInteger(0);
    private final AtomicInteger activeHeavyCount = new AtomicInteger(0);
    private final AtomicBoolean memoryThrottled = new AtomicBoolean(false);

    // Locks & Worker Threads
    private final ReentrantLock dispatchLock = new ReentrantLock();
    private final ExecutorService workerPool;
    private final ScheduledExecutorService dispatcherScheduler;
    private volatile MemoryAdvisor memoryAdvisor;

    public BackgroundTaskOrchestrator() {
        this(null, null, null);
    }

    public BackgroundTaskOrchestrator(ExecutorService workerPool,
                                      ScheduledExecutorService scheduler,
                                      MemoryAdvisor advisor) {
        this.workerPool = workerPool != null ? workerPool : Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "codelens-task-worker");
            t.setDaemon(true);
            return t;
        });

        this.dispatcherScheduler = scheduler != null ? scheduler : Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "codelens-task-dispatcher");
            t.setDaemon(true);
            return t;
        });

        this.memoryAdvisor = advisor != null ? advisor : createDefaultMemoryAdvisor(null);

        // Periodic check to re-evaluate throttled tasks and memory recovery
        this.dispatcherScheduler.scheduleWithFixedDelay(this::dispatch, 500, 500, TimeUnit.MILLISECONDS);

        // Pre-register standard CodeLens background processes
        registerStandardTasks();
    }

    public void setMemoryAdvisor(MemoryAdvisor advisor) {
        this.memoryAdvisor = advisor != null ? advisor : createDefaultMemoryAdvisor(null);
    }

    public void setHeapAutoRecoveryManager(HeapAutoRecoveryManager manager) {
        this.memoryAdvisor = createDefaultMemoryAdvisor(manager);
        if (manager != null) {
            manager.setOnCircuitBreakerReset(this::dispatch);
        }
    }

    private MemoryAdvisor createDefaultMemoryAdvisor(HeapAutoRecoveryManager manager) {
        return new MemoryAdvisor() {
            @Override
            public int getHeapUsagePercent() {
                long free = Runtime.getRuntime().freeMemory();
                long total = Runtime.getRuntime().totalMemory();
                long max = Runtime.getRuntime().maxMemory();
                long used = total - free;
                return max > 0 ? (int) ((used * 100L) / max) : 0;
            }

            @Override
            public boolean isCircuitBreakerActive() {
                return manager != null && manager.isCircuitBreakerActive();
            }

            @Override
            public void triggerRecovery(String reason) {
                if (manager != null) {
                    manager.triggerAutoRecovery(reason);
                } else {
                    System.gc();
                }
            }
        };
    }

    /**
     * Register a standard task type with its classification, capacity weight, mutex, and prerequisites.
     */
    public void registerTask(TaskDefinition def) {
        registry.put(def.id, def);
        taskStates.computeIfAbsent(def.id, TaskState::new);
    }

    private void registerStandardTasks() {
        // 1. Full Source Scanner: Heavy, wipes DB tables, bulk mode
        registerTask(new TaskDefinition("scanner", "Source Code Scanner", LoadTier.HEAVY, 10,
                MutexGroup.DATABASE_EXCLUSIVE, Priority.HIGH, Collections.emptySet(), null));

        // 2. Incremental Delta Scanner: Heavy, DB exclusive
        registerTask(new TaskDefinition("delta-scanner", "Delta Change Scanner", LoadTier.HEAVY, 8,
                MutexGroup.DATABASE_EXCLUSIVE, Priority.HIGH, Collections.emptySet(), null));

        // 3. Stress Test Runner: Heavy, DB exclusive
        registerTask(new TaskDefinition("stress-test", "Scale & Stress Test Runner", LoadTier.HEAVY, 10,
                MutexGroup.DATABASE_EXCLUSIVE, Priority.HIGH, Collections.emptySet(), null));

        // 4. Database Maintenance: Heavy, DB exclusive
        registerTask(new TaskDefinition("db-maintenance", "Database Compaction & Maintenance", LoadTier.HEAVY, 10,
                MutexGroup.DATABASE_EXCLUSIVE, Priority.URGENT, Collections.emptySet(), null));

        // 5. Call Graph & Topology: Medium, waits for scanner bulk load
        registerTask(new TaskDefinition("call-graph", "Call Graph & Topology Engine", LoadTier.MEDIUM, 4,
                MutexGroup.NONE, Priority.NORMAL, Set.of("scanner"), null));

        // 6. Layout Precomputation: Medium, depends on call-graph
        registerTask(new TaskDefinition("layout-engine", "Sunflower Layout Precomputer", LoadTier.MEDIUM, 4,
                MutexGroup.NONE, Priority.NORMAL, Set.of("call-graph"), null));

        // 7. Module Analyzer: Medium, depends on scanner
        registerTask(new TaskDefinition("module-analyzer", "Module Dependency Engine", LoadTier.MEDIUM, 4,
                MutexGroup.NONE, Priority.NORMAL, Set.of("scanner"), null));

        // 8. Lucene Indexer: Medium, depends on scanner
        registerTask(new TaskDefinition("lucene-indexer", "Lucene Full-Text Search Indexer", LoadTier.MEDIUM, 4,
                MutexGroup.NONE, Priority.NORMAL, Set.of("scanner"), null));

        // 9. Git Analyzer: Medium, depends on scanner
        registerTask(new TaskDefinition("git-analyzer", "Git Churn & Hotspot Analyzer", LoadTier.MEDIUM, 4,
                MutexGroup.NONE, Priority.NORMAL, Set.of("scanner"), null));

        // 10. Reports Generator: Heavy, analysis exclusive, depends on graph, modules, and search
        registerTask(new TaskDefinition("reports-generator", "Intelligence Reports Generator", LoadTier.HEAVY, 7,
                MutexGroup.ANALYSIS_EXCLUSIVE, Priority.NORMAL,
                Set.of("call-graph", "module-analyzer", "lucene-indexer"), null));

        // 11. Database Connection Watchdog: Light
        registerTask(new TaskDefinition("db-watchdog", "Database Connection Watchdog", LoadTier.LIGHT, 1,
                MutexGroup.NONE, Priority.LOW, Collections.emptySet(), null));

        // 12. Heap Watchdog: Sentinel
        registerTask(new TaskDefinition("heap-watchdog", "Heap Auto-Recovery Watchdog", LoadTier.SENTINEL, 0,
                MutexGroup.NONE, Priority.LOW, Collections.emptySet(), null));

        // 13. SSE Live Telemetry Broadcaster: Light
        registerTask(new TaskDefinition("sse-broadcaster", "SSE Live Telemetry Broadcaster", LoadTier.LIGHT, 1,
                MutexGroup.NONE, Priority.LOW, Collections.emptySet(), null));
    }

    /**
     * Submit a task for execution with default parameters from registration.
     * Coalesces duplicate requests if task is already queued.
     */
    public QueuedTask submit(String taskId, Runnable action) {
        TaskDefinition def = registry.get(taskId);
        Priority prio = def != null ? def.defaultPriority : Priority.NORMAL;
        return submit(taskId, prio, action);
    }

    /**
     * Submit a task with specific priority.
     */
    public QueuedTask submit(String taskId, Priority priority, Runnable action) {
        TaskDefinition def = registry.get(taskId);
        String name = def != null ? def.name : taskId;
        LoadTier tier = def != null ? def.tier : LoadTier.MEDIUM;
        int units = def != null ? def.loadUnits : tier.getDefaultUnits();
        MutexGroup mutex = def != null ? def.mutexGroup : MutexGroup.NONE;
        Set<String> deps = def != null ? def.dependencies : Collections.emptySet();

        return submit(new QueuedTask(taskId, name, tier, units, mutex, priority, deps, action));
    }

    /**
     * Submit a fully formed QueuedTask.
     * Handles coalescing: if an identical task is already queued, returns the existing instance.
     */
    public QueuedTask submit(QueuedTask task) {
        dispatchLock.lock();
        try {
            // Check for existing queued task with same ID (coalescing)
            for (QueuedTask existing : queue) {
                if (existing.taskId.equals(task.taskId)) {
                    log.info("Coalesced task request for '{}' into existing queued task (position #{})",
                            task.taskId, queue.indexOf(existing) + 1);
                    if (task.priority.getLevel() > existing.priority.getLevel()) {
                        existing.priority = task.priority; // elevate priority
                    }
                    return existing;
                }
            }

            task.status = TaskStatus.QUEUED;
            queue.add(task);
            TaskState state = taskStates.computeIfAbsent(task.taskId, TaskState::new);
            state.lastStatus = TaskStatus.QUEUED;

            log.info("Enqueued task '{}' [Tier: {}, Units: {}, Mutex: {}, Priority: {}]. Queue size: {}",
                    task.taskId, task.loadTier, task.loadUnits, task.mutexGroup, task.priority, queue.size());
        } finally {
            dispatchLock.unlock();
        }

        // Trigger immediate dispatch
        dispatch();
        return task;
    }

    /**
     * Cancel a queued or running task.
     */
    public boolean cancel(String taskId) {
        boolean cancelled = false;
        dispatchLock.lock();
        try {
            // 1. Remove from queue if present
            Iterator<QueuedTask> it = queue.iterator();
            while (it.hasNext()) {
                QueuedTask q = it.next();
                if (q.taskId.equals(taskId)) {
                    q.cancelRequested.set(true);
                    q.status = TaskStatus.CANCELLED;
                    q.endTime = System.currentTimeMillis();
                    it.remove();
                    cancelled = true;
                    TaskState state = taskStates.get(taskId);
                    if (state != null) state.lastStatus = TaskStatus.CANCELLED;
                    log.info("Cancelled queued task '{}'", taskId);
                }
            }

            // 2. Signal running task if active
            QueuedTask running = activeRunningTasks.get(taskId);
            if (running != null) {
                running.cancelRequested.set(true);
                if (running.future != null) {
                    running.future.cancel(true);
                }
                if (running.runningThread != null) {
                    running.runningThread.interrupt();
                }
                cancelled = true;
                log.info("Signaled cancellation to actively running task '{}'", taskId);
            }
        } finally {
            dispatchLock.unlock();
        }

        if (cancelled) {
            dispatch();
        }
        return cancelled;
    }

    /**
     * Core orchestrator dispatch loop.
     * Evaluates dependencies, memory ceilings, mutex groups, and load capacity.
     */
    public void dispatch() {
        if (!dispatchLock.tryLock()) {
            return; // Another thread is currently executing dispatch
        }

        try {
            // 1. Memory Pressure Assessment
            int heapPct = memoryAdvisor != null ? memoryAdvisor.getHeapUsagePercent() : 0;
            boolean cbActive = memoryAdvisor != null && memoryAdvisor.isCircuitBreakerActive();
            boolean criticalMemory = heapPct >= MEMORY_CRITICAL_HEAP_PCT || cbActive;
            boolean safeMemory = heapPct < MEMORY_RESUME_HEAP_PCT && !cbActive;

            if (criticalMemory) {
                if (!memoryThrottled.getAndSet(true)) {
                    log.warn("Memory pressure high ({}% heap, cb={}). Throttling heavy background tasks & requesting auto-recovery.",
                            heapPct, cbActive);
                    if (memoryAdvisor != null) {
                        memoryAdvisor.triggerRecovery("ORCHESTRATOR_THROTTLE (" + heapPct + "% heap)");
                    }
                }
            } else if (safeMemory) {
                if (memoryThrottled.getAndSet(false)) {
                    log.info("Memory pressure normalized ({}% heap). Resuming queued background tasks.", heapPct);
                }
            }

            if (queue.isEmpty()) {
                return;
            }

            // 2. Sort queue by Priority (descending) then by Submit Timestamp (FIFO)
            queue.sort((a, b) -> {
                int cmp = Integer.compare(b.priority.getLevel(), a.priority.getLevel());
                if (cmp != 0) return cmp;
                return Long.compare(a.submitTimestamp, b.submitTimestamp);
            });

            // 3. Evaluate each task in the queue
            Iterator<QueuedTask> it = queue.iterator();
            while (it.hasNext()) {
                QueuedTask task = it.next();

                // Check dependencies
                Set<String> blocking = checkDependencies(task);
                if (!blocking.isEmpty()) {
                    task.status = TaskStatus.WAITING_DEPENDENCY;
                    task.waitingFor.clear();
                    task.waitingFor.addAll(blocking);
                    TaskState state = taskStates.get(task.taskId);
                    if (state != null) state.lastStatus = TaskStatus.WAITING_DEPENDENCY;
                    continue; // Skip this task for now
                } else {
                    task.waitingFor.clear();
                    if (task.status == TaskStatus.WAITING_DEPENDENCY) {
                        task.status = TaskStatus.QUEUED;
                    }
                }

                // Check memory throttle for HEAVY / MEDIUM tasks
                if (memoryThrottled.get() && (task.loadTier == LoadTier.HEAVY || task.loadTier == LoadTier.MEDIUM)) {
                    task.status = TaskStatus.THROTTLED;
                    task.throttleReason = String.format("Throttled: JVM heap at %d%% >= %d%%%s",
                            heapPct, MEMORY_CRITICAL_HEAP_PCT, cbActive ? " (circuit breaker active)" : "");
                    TaskState state = taskStates.get(task.taskId);
                    if (state != null) state.lastStatus = TaskStatus.THROTTLED;
                    continue; // Wait until memory normalizes
                } else {
                    task.throttleReason = null;
                    if (task.status == TaskStatus.THROTTLED) {
                        task.status = TaskStatus.QUEUED;
                    }
                }

                // Check Mutex
                if (task.mutexGroup != MutexGroup.NONE && activeMutexes.contains(task.mutexGroup)) {
                    continue; // Mutex group occupied
                }

                // Check Heavy Slot Capacity (MAX_HEAVY_CONCURRENT = 1)
                if (task.loadTier == LoadTier.HEAVY && activeHeavyCount.get() >= MAX_HEAVY_CONCURRENT) {
                    continue; // Heavy slot occupied
                }

                // Check Total Load Units Capacity (MAX_LOAD_UNITS = 10)
                if (activeLoadUnits.get() + task.loadUnits > MAX_LOAD_UNITS) {
                    continue; // Exceeds load capacity
                }

                // All criteria satisfied: Allocate permits and launch task!
                it.remove();
                allocateAndLaunch(task);
            }

        } finally {
            dispatchLock.unlock();
        }
    }

    /**
     * Verify whether all prerequisites and DB-exclusive conditions are satisfied.
     * Returns the set of blocking task IDs (empty if all satisfied).
     */
    private Set<String> checkDependencies(QueuedTask task) {
        Set<String> blocking = new LinkedHashSet<>();

        // If a task reads the database, it must not run while a DATABASE_EXCLUSIVE task is active
        boolean readsDatabase = !"scanner".equals(task.taskId) &&
                !"delta-scanner".equals(task.taskId) &&
                !"stress-test".equals(task.taskId) &&
                !"db-maintenance".equals(task.taskId);

        if (readsDatabase) {
            for (String activeId : activeRunningTasks.keySet()) {
                TaskDefinition def = registry.get(activeId);
                if (def != null && def.mutexGroup == MutexGroup.DATABASE_EXCLUSIVE) {
                    blocking.add(activeId);
                }
            }
        }

        // Check explicitly declared dependencies
        for (String depId : task.dependencies) {
            // 1. Is dependency currently executing?
            if (activeRunningTasks.containsKey(depId)) {
                blocking.add(depId);
                continue;
            }

            // 2. Is dependency queued ahead of this task?
            boolean queuedAhead = false;
            for (QueuedTask q : queue) {
                if (q == task) break;
                if (q.taskId.equals(depId)) {
                    queuedAhead = true;
                    break;
                }
            }
            if (queuedAhead) {
                blocking.add(depId);
                continue;
            }

            // 3. Has dependency ever completed or is it ready?
            TaskDefinition def = registry.get(depId);
            if (def != null && def.isReadySupplier != null) {
                try {
                    if (!def.isReadySupplier.getAsBoolean()) {
                        blocking.add(depId);
                    }
                } catch (Exception ignored) {
                    blocking.add(depId);
                }
            } else {
                TaskState depState = taskStates.get(depId);
                if (depState == null || (depState.lastStatus != TaskStatus.COMPLETE && depState.lastStatus != TaskStatus.IDLE)) {
                    // Dep has not completed successfully yet
                    blocking.add(depId);
                }
            }
        }

        return blocking;
    }

    /**
     * Reserve concurrency permits, update status, and submit to worker pool.
     */
    private void allocateAndLaunch(QueuedTask task) {
        task.status = TaskStatus.RUNNING;
        task.startTime = System.currentTimeMillis();
        activeRunningTasks.put(task.taskId, task);

        if (task.mutexGroup != MutexGroup.NONE) {
            activeMutexes.add(task.mutexGroup);
        }
        if (task.loadTier == LoadTier.HEAVY) {
            activeHeavyCount.incrementAndGet();
        }
        activeLoadUnits.addAndGet(task.loadUnits);

        TaskState state = taskStates.computeIfAbsent(task.taskId, TaskState::new);
        state.lastStatus = TaskStatus.RUNNING;
        state.lastStartTime = task.startTime;

        log.info("Launching background task '{}' [Active Units: {}/10, Heavy: {}/1]",
                task.taskId, activeLoadUnits.get(), activeHeavyCount.get());

        task.future = workerPool.submit(() -> {
            task.runningThread = Thread.currentThread();
            try {
                task.action.run();
                if (task.cancelRequested.get()) {
                    task.status = TaskStatus.CANCELLED;
                } else {
                    task.status = TaskStatus.COMPLETE;
                }
                task.errorDetail = null;
            } catch (Throwable t) {
                if (task.cancelRequested.get() || t instanceof InterruptedException) {
                    task.status = TaskStatus.CANCELLED;
                    task.errorDetail = "Task cancelled";
                } else {
                    log.error("Background task '{}' encountered an error: {}", task.taskId, t.getMessage(), t);
                    task.status = TaskStatus.ERROR;
                    task.errorDetail = t.getMessage() != null ? t.getMessage() : t.toString();
                }
            } finally {
                task.endTime = System.currentTimeMillis();
                task.durationMs = task.endTime - task.startTime;

                // Release permits
                activeRunningTasks.remove(task.taskId);
                if (task.mutexGroup != MutexGroup.NONE) {
                    activeMutexes.remove(task.mutexGroup);
                }
                if (task.loadTier == LoadTier.HEAVY) {
                    activeHeavyCount.decrementAndGet();
                }
                activeLoadUnits.addAndGet(-task.loadUnits);

                // Update persistent state
                state.lastStatus = task.status;
                state.lastEndTime = task.endTime;
                state.lastDurationMs = task.durationMs;
                state.lastErrorDetail = task.errorDetail;

                log.info("Finished background task '{}' with status {} in {} ms [Remaining Units: {}/10]",
                        task.taskId, task.status, task.durationMs, activeLoadUnits.get());

                // Trigger next dispatch
                dispatch();
            }
        });
    }

    /**
     * Check if a task is currently executing.
     */
    public boolean isRunning(String taskId) {
        return activeRunningTasks.containsKey(taskId);
    }

    /**
     * Check if a task is currently queued or waiting.
     */
    public boolean isQueued(String taskId) {
        dispatchLock.lock();
        try {
            for (QueuedTask q : queue) {
                if (q.taskId.equals(taskId)) return true;
            }
            return false;
        } finally {
            dispatchLock.unlock();
        }
    }

    /**
     * Get real-time snapshot for a task.
     */
    public TaskSnapshot getTaskSnapshot(String taskId) {
        dispatchLock.lock();
        try {
            // 1. Is it actively running?
            QueuedTask running = activeRunningTasks.get(taskId);
            if (running != null) {
                return new TaskSnapshot(running.taskId, running.taskName, running.loadTier.name(),
                        running.loadUnits, running.mutexGroup.name(), TaskStatus.RUNNING, 0,
                        null, null, running.startTime, 0,
                        System.currentTimeMillis() - running.startTime, null);
            }

            // 2. Is it in queue?
            for (int i = 0; i < queue.size(); i++) {
                QueuedTask q = queue.get(i);
                if (q.taskId.equals(taskId)) {
                    return new TaskSnapshot(q.taskId, q.taskName, q.loadTier.name(),
                            q.loadUnits, q.mutexGroup.name(), q.status, i + 1,
                            new ArrayList<>(q.waitingFor), q.throttleReason,
                            q.submitTimestamp, 0, 0, null);
                }
            }

            // 3. Fall back to historical state / registry definition
            TaskState state = taskStates.get(taskId);
            TaskDefinition def = registry.get(taskId);
            String name = def != null ? def.name : taskId;
            String tier = def != null ? def.tier.name() : LoadTier.MEDIUM.name();
            int units = def != null ? def.loadUnits : 4;
            String mutex = def != null ? def.mutexGroup.name() : MutexGroup.NONE.name();
            TaskStatus status = state != null ? state.lastStatus : TaskStatus.IDLE;

            return new TaskSnapshot(taskId, name, tier, units, mutex, status, 0,
                    null, null,
                    state != null ? state.lastStartTime : 0,
                    state != null ? state.lastEndTime : 0,
                    state != null ? state.lastDurationMs : 0,
                    state != null ? state.lastErrorDetail : null);
        } finally {
            dispatchLock.unlock();
        }
    }

    /**
     * Generate complete telemetry snapshot of the orchestrator state.
     */
    public Map<String, Object> getOrchestratorSnapshot() {
        dispatchLock.lock();
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("activeLoadUnits", activeLoadUnits.get());
            snap.put("maxLoadUnits", MAX_LOAD_UNITS);
            snap.put("activeHeavyCount", activeHeavyCount.get());
            snap.put("maxHeavyConcurrent", MAX_HEAVY_CONCURRENT);
            snap.put("queuedCount", queue.size());
            snap.put("runningCount", activeRunningTasks.size());
            snap.put("isMemoryThrottled", memoryThrottled.get());
            snap.put("activeTasks", new ArrayList<>(activeRunningTasks.keySet()));

            List<Map<String, Object>> queuedList = new ArrayList<>();
            for (int i = 0; i < queue.size(); i++) {
                QueuedTask q = queue.get(i);
                Map<String, Object> qm = new LinkedHashMap<>();
                qm.put("position", i + 1);
                qm.put("id", q.taskId);
                qm.put("name", q.taskName);
                qm.put("loadUnits", q.loadUnits);
                qm.put("loadTier", q.loadTier.name());
                qm.put("status", q.status.name());
                qm.put("priority", q.priority.name());
                qm.put("waitingFor", new ArrayList<>(q.waitingFor));
                if (q.throttleReason != null) qm.put("throttleReason", q.throttleReason);
                queuedList.add(qm);
            }
            snap.put("queue", queuedList);

            return snap;
        } finally {
            dispatchLock.unlock();
        }
    }

    /**
     * Gracefully shutdown the orchestrator and its worker pools.
     */
    public void shutdown() {
        dispatcherScheduler.shutdownNow();
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
        }
        log.info("BackgroundTaskOrchestrator successfully shut down.");
    }
}
