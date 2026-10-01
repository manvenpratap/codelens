package com.codelens.api;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class BackgroundTaskOrchestratorTest {

    private void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " - Expected: " + expected + ", Actual: " + actual);
        }
    }

    private void assertNotNull(Object obj, String message) {
        if (obj == null) {
            throw new AssertionError(message);
        }
    }

    /**
     * Test 1: Heavy Task Concurrency Limiter
     * Only ONE heavy task can run at any given time.
     */
    public void testHeavyTaskConcurrencyLimiter() throws Exception {
        BackgroundTaskOrchestrator orchestrator = new BackgroundTaskOrchestrator();
        try {
            CountDownLatch task1Started = new CountDownLatch(1);
            CountDownLatch task1Blocker = new CountDownLatch(1);
            AtomicInteger maxConcurrentHeavy = new AtomicInteger(0);

            // Task 1: Scanner (Heavy, 10 units)
            orchestrator.submit("scanner", () -> {
                int heavyNow = ((Number) orchestrator.getOrchestratorSnapshot().get("activeHeavyCount")).intValue();
                maxConcurrentHeavy.updateAndGet(m -> Math.max(m, heavyNow));
                task1Started.countDown();
                try {
                    task1Blocker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
            });

            assertTrue(task1Started.await(3, TimeUnit.SECONDS), "Task 1 should have started");

            // Task 2: Stress Test (Heavy, 10 units)
            CountDownLatch task2Finished = new CountDownLatch(1);
            orchestrator.submit("stress-test", () -> {
                int heavyNow = ((Number) orchestrator.getOrchestratorSnapshot().get("activeHeavyCount")).intValue();
                maxConcurrentHeavy.updateAndGet(m -> Math.max(m, heavyNow));
                task2Finished.countDown();
            });

            // Verify task 2 is queued because task 1 holds the heavy slot & database mutex
            Thread.sleep(100);
            BackgroundTaskOrchestrator.TaskSnapshot snap2 = orchestrator.getTaskSnapshot("stress-test");
            assertTrue(snap2.status == BackgroundTaskOrchestrator.TaskStatus.QUEUED ||
                       snap2.status == BackgroundTaskOrchestrator.TaskStatus.WAITING_DEPENDENCY,
                       "Task 2 must be queued while Task 1 is running: " + snap2.status);
            assertEquals(1, snap2.queuePosition, "Task 2 should be in queue position 1");

            // Unblock task 1
            task1Blocker.countDown();

            // Task 2 should now execute and finish
            assertTrue(task2Finished.await(3, TimeUnit.SECONDS), "Task 2 should finish after Task 1 completes");
            assertEquals(1, maxConcurrentHeavy.get(), "Maximum concurrent heavy tasks should NEVER exceed 1");
        } finally {
            orchestrator.shutdown();
        }
    }

    /**
     * Test 2: Load Capacity Limiter (MAX_LOAD_UNITS = 10)
     * 3 medium tasks (4 units each = 12) -> only 2 can run at once, 3rd waits.
     */
    public void testLoadCapacityLimiter() throws Exception {
        BackgroundTaskOrchestrator orchestrator = new BackgroundTaskOrchestrator();
        try {
            // Register three independent medium tasks (4 units each, no deps, no mutex)
            orchestrator.registerTask(new BackgroundTaskOrchestrator.TaskDefinition(
                    "med-1", "Medium 1", BackgroundTaskOrchestrator.LoadTier.MEDIUM, 4,
                    BackgroundTaskOrchestrator.MutexGroup.NONE, BackgroundTaskOrchestrator.Priority.NORMAL,
                    Collections.emptySet(), null));
            orchestrator.registerTask(new BackgroundTaskOrchestrator.TaskDefinition(
                    "med-2", "Medium 2", BackgroundTaskOrchestrator.LoadTier.MEDIUM, 4,
                    BackgroundTaskOrchestrator.MutexGroup.NONE, BackgroundTaskOrchestrator.Priority.NORMAL,
                    Collections.emptySet(), null));
            orchestrator.registerTask(new BackgroundTaskOrchestrator.TaskDefinition(
                    "med-3", "Medium 3", BackgroundTaskOrchestrator.LoadTier.MEDIUM, 4,
                    BackgroundTaskOrchestrator.MutexGroup.NONE, BackgroundTaskOrchestrator.Priority.NORMAL,
                    Collections.emptySet(), null));

            CountDownLatch m1Started = new CountDownLatch(1);
            CountDownLatch m2Started = new CountDownLatch(1);
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch m3Finished = new CountDownLatch(1);
            AtomicInteger maxUnitsSeen = new AtomicInteger(0);

            orchestrator.submit("med-1", () -> {
                int units = ((Number) orchestrator.getOrchestratorSnapshot().get("activeLoadUnits")).intValue();
                maxUnitsSeen.updateAndGet(m -> Math.max(m, units));
                m1Started.countDown();
                try { blocker.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });

            orchestrator.submit("med-2", () -> {
                int units = ((Number) orchestrator.getOrchestratorSnapshot().get("activeLoadUnits")).intValue();
                maxUnitsSeen.updateAndGet(m -> Math.max(m, units));
                m2Started.countDown();
                try { blocker.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });

            assertTrue(m1Started.await(3, TimeUnit.SECONDS), "Med 1 should start");
            assertTrue(m2Started.await(3, TimeUnit.SECONDS), "Med 2 should start");

            // Submit 3rd task (4 units, total would be 4+4+4 = 12 > 10)
            orchestrator.submit("med-3", () -> {
                int units = ((Number) orchestrator.getOrchestratorSnapshot().get("activeLoadUnits")).intValue();
                maxUnitsSeen.updateAndGet(m -> Math.max(m, units));
                m3Finished.countDown();
            });

            Thread.sleep(100);
            BackgroundTaskOrchestrator.TaskSnapshot snap3 = orchestrator.getTaskSnapshot("med-3");
            assertEquals(BackgroundTaskOrchestrator.TaskStatus.QUEUED, snap3.status, "Med 3 must wait in queue for capacity");

            // Release med-1 and med-2
            blocker.countDown();

            assertTrue(m3Finished.await(3, TimeUnit.SECONDS), "Med 3 should finish once capacity is freed");
            assertTrue(maxUnitsSeen.get() <= 10, "Total active load units must never exceed 10 (seen: " + maxUnitsSeen.get() + ")");
        } finally {
            orchestrator.shutdown();
        }
    }

    /**
     * Test 3: Dependency Resolution
     * layout-engine must wait for call-graph to finish.
     */
    public void testDependencyResolution() throws Exception {
        BackgroundTaskOrchestrator orchestrator = new BackgroundTaskOrchestrator();
        try {
            CountDownLatch graphStarted = new CountDownLatch(1);
            CountDownLatch graphBlocker = new CountDownLatch(1);
            CountDownLatch layoutFinished = new CountDownLatch(1);
            List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

            orchestrator.submit("call-graph", () -> {
                executionOrder.add("call-graph-start");
                graphStarted.countDown();
                try { graphBlocker.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                executionOrder.add("call-graph-end");
            });

            assertTrue(graphStarted.await(3, TimeUnit.SECONDS), "Call graph should start");

            // Submit layout-engine which depends on call-graph
            orchestrator.submit("layout-engine", () -> {
                executionOrder.add("layout-engine-start");
                layoutFinished.countDown();
            });

            Thread.sleep(100);
            BackgroundTaskOrchestrator.TaskSnapshot layoutSnap = orchestrator.getTaskSnapshot("layout-engine");
            assertEquals(BackgroundTaskOrchestrator.TaskStatus.WAITING_DEPENDENCY, layoutSnap.status,
                    "Layout engine must be in WAITING_DEPENDENCY state");
            assertTrue(layoutSnap.waitingFor.contains("call-graph"), "Layout engine waiting for call-graph");

            // Unblock call-graph
            graphBlocker.countDown();

            assertTrue(layoutFinished.await(3, TimeUnit.SECONDS), "Layout engine should finish after call graph");
            assertEquals(List.of("call-graph-start", "call-graph-end", "layout-engine-start"), executionOrder,
                    "Layout engine must strictly execute after call-graph completes");
        } finally {
            orchestrator.shutdown();
        }
    }

    /**
     * Test 4: Memory Sentinel Throttling & Auto-Recovery
     * If heap >= 85%, heavy/medium tasks are THROTTLED and auto-recovery is invoked.
     */
    public void testMemorySentinelThrottling() throws Exception {
        AtomicInteger mockHeapPct = new AtomicInteger(90); // Start in critical memory pressure
        AtomicBoolean mockCircuitBreaker = new AtomicBoolean(false);
        AtomicInteger recoveryAttempts = new AtomicInteger(0);

        BackgroundTaskOrchestrator.MemoryAdvisor mockAdvisor = new BackgroundTaskOrchestrator.MemoryAdvisor() {
            @Override
            public int getHeapUsagePercent() {
                return mockHeapPct.get();
            }

            @Override
            public boolean isCircuitBreakerActive() {
                return mockCircuitBreaker.get();
            }

            @Override
            public void triggerRecovery(String reason) {
                recoveryAttempts.incrementAndGet();
            }
        };

        BackgroundTaskOrchestrator orchestrator = new BackgroundTaskOrchestrator(null, null, mockAdvisor);
        try {
            CountDownLatch taskFinished = new CountDownLatch(1);

            orchestrator.submit("reports-generator", taskFinished::countDown);

            Thread.sleep(150);
            BackgroundTaskOrchestrator.TaskSnapshot snap = orchestrator.getTaskSnapshot("reports-generator");
            assertEquals(BackgroundTaskOrchestrator.TaskStatus.THROTTLED, snap.status,
                    "Task should be THROTTLED under 90% heap");
            assertNotNull(snap.throttleReason, "Throttle reason should be present");
            assertTrue(recoveryAttempts.get() >= 1, "Auto-recovery should have been triggered");

            // Simulate memory recovery (< 80%)
            mockHeapPct.set(65);
            orchestrator.dispatch();

            assertTrue(taskFinished.await(3, TimeUnit.SECONDS), "Task should resume and complete after memory normalizes");
            assertEquals(BackgroundTaskOrchestrator.TaskStatus.COMPLETE,
                    orchestrator.getTaskSnapshot("reports-generator").status,
                    "Task status should be COMPLETE");
        } finally {
            orchestrator.shutdown();
        }
    }

    /**
     * Test 5: Request Coalescing / Deduplication
     * Multiple submissions of the same task in the queue coalesce into one.
     */
    public void testRequestCoalescing() throws Exception {
        BackgroundTaskOrchestrator orchestrator = new BackgroundTaskOrchestrator();
        try {
            CountDownLatch blocker = new CountDownLatch(1);
            orchestrator.submit("scanner", () -> {
                try { blocker.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });

            // Enqueue layout-engine 3 times while blocked
            BackgroundTaskOrchestrator.QueuedTask q1 = orchestrator.submit("layout-engine", () -> {});
            BackgroundTaskOrchestrator.QueuedTask q2 = orchestrator.submit("layout-engine", () -> {});
            BackgroundTaskOrchestrator.QueuedTask q3 = orchestrator.submit("layout-engine", () -> {});

            assertEquals(q1, q2, "Duplicate submission 2 should coalesce into q1");
            assertEquals(q1, q3, "Duplicate submission 3 should coalesce into q1");

            Map<String, Object> snap = orchestrator.getOrchestratorSnapshot();
            assertEquals(1, snap.get("queuedCount"), "Queue must only contain 1 queued instance of layout-engine");

            blocker.countDown();
        } finally {
            orchestrator.shutdown();
        }
    }

    /**
     * Test 6: Task Cancellation
     */
    public void testTaskCancellation() throws Exception {
        BackgroundTaskOrchestrator orchestrator = new BackgroundTaskOrchestrator();
        try {
            CountDownLatch blocker = new CountDownLatch(1);
            orchestrator.submit("scanner", () -> {
                try { blocker.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });

            orchestrator.submit("layout-engine", () -> {});
            assertTrue(orchestrator.isQueued("layout-engine"), "layout-engine should be in queue");

            boolean cancelled = orchestrator.cancel("layout-engine");
            assertTrue(cancelled, "Task should be cancelled");
            assertFalse(orchestrator.isQueued("layout-engine"), "layout-engine should no longer be in queue");
            assertEquals(BackgroundTaskOrchestrator.TaskStatus.CANCELLED,
                    orchestrator.getTaskSnapshot("layout-engine").status, "Status should be CANCELLED");

            blocker.countDown();
        } finally {
            orchestrator.shutdown();
        }
    }

    /**
     * Test 7: Database Exclusive Mutex Blocks Reads
     * When scanner is active, DB-reading tasks like module-analyzer must wait in WAITING_DEPENDENCY.
     */
    public void testDatabaseExclusiveMutexBlocksReads() throws Exception {
        BackgroundTaskOrchestrator orchestrator = new BackgroundTaskOrchestrator();
        try {
            CountDownLatch scannerStarted = new CountDownLatch(1);
            CountDownLatch scannerBlocker = new CountDownLatch(1);
            CountDownLatch analyzerFinished = new CountDownLatch(1);

            orchestrator.submit("scanner", () -> {
                scannerStarted.countDown();
                try { scannerBlocker.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });

            assertTrue(scannerStarted.await(3, TimeUnit.SECONDS), "Scanner should start");

            // Submit module-analyzer which reads DB
            orchestrator.submit("module-analyzer", analyzerFinished::countDown);

            Thread.sleep(100);
            BackgroundTaskOrchestrator.TaskSnapshot snap = orchestrator.getTaskSnapshot("module-analyzer");
            assertEquals(BackgroundTaskOrchestrator.TaskStatus.WAITING_DEPENDENCY, snap.status,
                    "Module analyzer must wait while scanner is executing");
            assertTrue(snap.waitingFor.contains("scanner"), "Waiting reason should include scanner");

            scannerBlocker.countDown();

            assertTrue(analyzerFinished.await(3, TimeUnit.SECONDS), "Module analyzer should finish after scanner completes");
        } finally {
            orchestrator.shutdown();
        }
    }

    /**
     * Test 8: Priority Ordering
     * An URGENT or HIGH priority task must run ahead of a NORMAL priority task.
     */
    public void testPriorityOrdering() throws Exception {
        BackgroundTaskOrchestrator orchestrator = new BackgroundTaskOrchestrator();
        try {
            CountDownLatch blocker = new CountDownLatch(1);
            orchestrator.submit("scanner", () -> {
                try { blocker.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });

            // Enqueue Low priority task first
            orchestrator.submit("db-watchdog", BackgroundTaskOrchestrator.Priority.LOW, () -> {});

            // Enqueue High priority task second
            orchestrator.submit("stress-test", BackgroundTaskOrchestrator.Priority.HIGH, () -> {});

            Thread.sleep(50);
            Map<String, Object> snap = orchestrator.getOrchestratorSnapshot();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> queue = (List<Map<String, Object>>) snap.get("queue");

            assertEquals(2, queue.size(), "Two tasks should be queued");
            assertEquals("stress-test", queue.get(0).get("id"), "High priority stress-test should be position #1");
            assertEquals("db-watchdog", queue.get(1).get("id"), "Low priority db-watchdog should be position #2");

            blocker.countDown();
        } finally {
            orchestrator.shutdown();
        }
    }
}
