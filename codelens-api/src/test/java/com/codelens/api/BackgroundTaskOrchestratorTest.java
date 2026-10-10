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
            long deadline = System.currentTimeMillis() + 1000;
            while (System.currentTimeMillis() < deadline && orchestrator.getTaskSnapshot("reports-generator").status != BackgroundTaskOrchestrator.TaskStatus.COMPLETE) {
                Thread.sleep(10);
            }
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

    /**
     * Test 9: Two-way Database Exclusivity - Exclusive Task Waits For Active DB Readers
     * If call-graph is currently reading DB, scanner or db-maintenance must wait in queue.
     */
    public void testDatabaseExclusiveWaitsForActiveDatabaseReaders() throws Exception {
        BackgroundTaskOrchestrator orchestrator = new BackgroundTaskOrchestrator();
        try {
            CountDownLatch readerStarted = new CountDownLatch(1);
            CountDownLatch readerBlocker = new CountDownLatch(1);
            CountDownLatch scannerFinished = new CountDownLatch(1);

            // 1. Launch a database reader (call-graph)
            orchestrator.submit("call-graph", () -> {
                readerStarted.countDown();
                try { readerBlocker.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });

            assertTrue(readerStarted.await(3, TimeUnit.SECONDS), "Reader task should start");

            // 2. Submit a DATABASE_EXCLUSIVE task (db-maintenance)
            orchestrator.submit("db-maintenance", BackgroundTaskOrchestrator.Priority.HIGH, scannerFinished::countDown);

            Thread.sleep(100);
            BackgroundTaskOrchestrator.TaskSnapshot snap = orchestrator.getTaskSnapshot("db-maintenance");
            assertTrue(snap.status == BackgroundTaskOrchestrator.TaskStatus.WAITING_DEPENDENCY ||
                       snap.status == BackgroundTaskOrchestrator.TaskStatus.QUEUED,
                       "Database-exclusive task must wait while DB reader is running: " + snap.status);
            assertTrue(snap.waitingFor.contains("call-graph"), "db-maintenance should be waiting for active call-graph");

            // 3. Unblock reader
            readerBlocker.countDown();

            // 4. Exclusive task should now execute and finish
            assertTrue(scannerFinished.await(3, TimeUnit.SECONDS), "db-maintenance should finish after reader completes");
        } finally {
            orchestrator.shutdown();
        }
    }

    /**
     * Test 10: Two-way Database Exclusivity - DB Reader Waits For Queued Exclusive Task
     * If scanner is queued, a newly enqueued DB reader must wait behind scanner.
     */
    public void testDatabaseReaderWaitsForQueuedDatabaseExclusiveTask() throws Exception {
        BackgroundTaskOrchestrator orchestrator = new BackgroundTaskOrchestrator();
        try {
            CountDownLatch heavy1Started = new CountDownLatch(1);
            CountDownLatch heavy1Blocker = new CountDownLatch(1);
            CountDownLatch scannerFinished = new CountDownLatch(1);
            CountDownLatch readerFinished = new CountDownLatch(1);
            List<String> order = Collections.synchronizedList(new ArrayList<>());

            // Task 1: occupy heavy slot with stress-test
            orchestrator.submit("stress-test", () -> {
                heavy1Started.countDown();
                try { heavy1Blocker.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });

            assertTrue(heavy1Started.await(3, TimeUnit.SECONDS), "Stress test should start");

            // Task 2: Queue scanner (DB exclusive)
            orchestrator.submit("scanner", BackgroundTaskOrchestrator.Priority.HIGH, () -> {
                order.add("scanner");
                scannerFinished.countDown();
            });

            // Task 3: Queue call-graph (DB reader)
            orchestrator.submit("call-graph", BackgroundTaskOrchestrator.Priority.NORMAL, () -> {
                order.add("call-graph");
                readerFinished.countDown();
            });

            Thread.sleep(100);
            BackgroundTaskOrchestrator.TaskSnapshot snap = orchestrator.getTaskSnapshot("call-graph");
            assertTrue(snap.status == BackgroundTaskOrchestrator.TaskStatus.WAITING_DEPENDENCY,
                       "Reader must wait for queued scanner");
            assertTrue(snap.waitingFor.contains("scanner"), "Reader should wait for queued scanner");

            heavy1Blocker.countDown();

            assertTrue(scannerFinished.await(3, TimeUnit.SECONDS), "Scanner should finish first");
            assertTrue(readerFinished.await(3, TimeUnit.SECONDS), "Call graph should finish after scanner");
            assertEquals(List.of("scanner", "call-graph"), order, "Scanner must run before call-graph");
        } finally {
            orchestrator.shutdown();
        }
    }

    /**
     * Test 11: CodeStory Pipeline Dependency Chain
     * scanner -> call-graph -> storylines-generator -> change-story-analyzer -> ai-grounding-engine
     */
    public void testCodeStoryPipelinesDependencyChain() throws Exception {
        BackgroundTaskOrchestrator orchestrator = new BackgroundTaskOrchestrator();
        try {
            CountDownLatch scannerBlocker = new CountDownLatch(1);
            CountDownLatch cgBlocker = new CountDownLatch(1);
            CountDownLatch storylinesFinished = new CountDownLatch(1);
            CountDownLatch changeStoryFinished = new CountDownLatch(1);
            CountDownLatch aiGroundingFinished = new CountDownLatch(1);
            List<String> timeline = Collections.synchronizedList(new ArrayList<>());

            orchestrator.submit("scanner", () -> {
                timeline.add("scanner-start");
                try { scannerBlocker.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                timeline.add("scanner-end");
            });

            orchestrator.submit("call-graph", () -> {
                timeline.add("call-graph-start");
                try { cgBlocker.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                timeline.add("call-graph-end");
            });

            orchestrator.submit("git-analyzer", () -> {
                timeline.add("git-analyzer-done");
            });

            orchestrator.submit("storylines-generator", () -> {
                timeline.add("storylines-done");
                storylinesFinished.countDown();
            });

            orchestrator.submit("change-story-analyzer", () -> {
                timeline.add("change-story-done");
                changeStoryFinished.countDown();
            });

            orchestrator.submit("ai-grounding-engine", () -> {
                timeline.add("ai-grounding-done");
                aiGroundingFinished.countDown();
            });

            Thread.sleep(100);
            // Verify storylines is waiting on call-graph and scanner
            BackgroundTaskOrchestrator.TaskSnapshot storySnap = orchestrator.getTaskSnapshot("storylines-generator");
            assertEquals(BackgroundTaskOrchestrator.TaskStatus.WAITING_DEPENDENCY, storySnap.status,
                    "Storylines should wait for call-graph");

            // Release scanner
            scannerBlocker.countDown();
            Thread.sleep(100);

            // Release call graph
            cgBlocker.countDown();

            assertTrue(storylinesFinished.await(3, TimeUnit.SECONDS), "Storylines should finish");
            assertTrue(changeStoryFinished.await(3, TimeUnit.SECONDS), "Change story should finish");
            assertTrue(aiGroundingFinished.await(3, TimeUnit.SECONDS), "AI grounding should finish");

            // Assert execution ordering
            int idxScanner = timeline.indexOf("scanner-end");
            int idxCg = timeline.indexOf("call-graph-end");
            int idxStories = timeline.indexOf("storylines-done");
            int idxChange = timeline.indexOf("change-story-done");
            int idxAi = timeline.indexOf("ai-grounding-done");

            assertTrue(idxScanner < idxCg, "Scanner must end before Call Graph ends");
            assertTrue(idxCg < idxStories, "Call Graph must end before Storylines");
            assertTrue(idxStories < idxChange, "Storylines must finish before Change Story");
            assertTrue(idxStories < idxAi, "Storylines must finish before AI Grounding");
        } finally {
            orchestrator.shutdown();
        }
    }

    /**
     * Test 12: Invalidation on Rescan
     * Enqueuing scanner resets downstream completed tasks so stale prerequisites don't leak.
     */
    public void testRescanInvalidatesDownstream() throws Exception {
        BackgroundTaskOrchestrator orchestrator = new BackgroundTaskOrchestrator();
        try {
            CountDownLatch taskDone = new CountDownLatch(1);
            orchestrator.submit("call-graph", taskDone::countDown);
            assertTrue(taskDone.await(3, TimeUnit.SECONDS), "Call graph should finish initially");

            long deadline = System.currentTimeMillis() + 1000;
            while (orchestrator.getTaskSnapshot("call-graph").status != BackgroundTaskOrchestrator.TaskStatus.COMPLETE && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }

            assertEquals(BackgroundTaskOrchestrator.TaskStatus.COMPLETE,
                    orchestrator.getTaskSnapshot("call-graph").status,
                    "Call graph should be COMPLETE");

            // Now enqueue scanner
            CountDownLatch scanBlocker = new CountDownLatch(1);
            orchestrator.submit("scanner", () -> {
                try { scanBlocker.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });

            // Verify call-graph was invalidated
            assertEquals(BackgroundTaskOrchestrator.TaskStatus.WAITING_DEPENDENCY,
                    orchestrator.getTaskSnapshot("call-graph").status,
                    "Call graph state should be invalidated upon scanner submission");

            scanBlocker.countDown();
        } finally {
            orchestrator.shutdown();
        }
    }
}
