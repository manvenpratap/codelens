package com.codelens.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class HeapAutoRecoveryTest {

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
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " - Expected: " + expected + ", Actual: " + actual);
        }
    }

    private void assertNotNull(Object obj, String message) {
        if (obj == null) {
            throw new AssertionError(message);
        }
    }

    public void testStatusAndMetrics() {
        HeapAutoRecoveryManager manager = new HeapAutoRecoveryManager();
        try {
            Map<String, Object> metrics = manager.getStatusAndMetrics();
            assertNotNull(metrics, "Metrics should not be null");
            assertFalse((Boolean) metrics.get("circuitBreakerActive"), "Circuit breaker should initially be closed");
            assertEquals(75, metrics.get("warningThresholdPct"), "Warning threshold");
            assertEquals(85, metrics.get("criticalThresholdPct"), "Critical threshold");
            assertEquals(92, metrics.get("emergencyThresholdPct"), "Emergency threshold");
            assertTrue((Integer) metrics.get("currentHeapPct") >= 0, "Heap pct >= 0");
            assertTrue((Long) metrics.get("currentHeapMaxMb") > 0, "Heap max > 0");
        } finally {
            manager.stopWatchdog();
        }
    }

    public void testExecuteRecoveryHooks() {
        HeapAutoRecoveryManager manager = new HeapAutoRecoveryManager();
        try {
            AtomicBoolean cacheHookRun = new AtomicBoolean(false);
            AtomicBoolean dbHookRun = new AtomicBoolean(false);

            manager.registerRecoveryHook("TestLayoutCacheClearer", () -> cacheHookRun.set(true));
            manager.registerRecoveryHook("TestDbTrimmer", () -> dbHookRun.set(true));

            HeapAutoRecoveryManager.AutoRecoveryIncident incident = manager.triggerAutoRecovery("UNIT_TEST_TRIGGER");

            assertNotNull(incident, "Incident should not be null");
            assertTrue(cacheHookRun.get(), "Layout cache clearer hook should have executed");
            assertTrue(dbHookRun.get(), "DB trimmer hook should have executed");
            assertTrue(incident.id.startsWith("INC-"), "Incident ID format");
            assertEquals("UNIT_TEST_TRIGGER", incident.trigger, "Incident trigger");
            assertTrue(incident.actions.size() >= 3, "Actions executed count");

            Map<String, Object> metrics = manager.getStatusAndMetrics();
            assertEquals(1L, metrics.get("totalRecoveries"), "Total recoveries count");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> incidents = (List<Map<String, Object>>) metrics.get("incidents");
            assertEquals(1, incidents.size(), "Incidents history size");
        } finally {
            manager.stopWatchdog();
        }
    }

    public void testHandleTrappedOOM() {
        HeapAutoRecoveryManager manager = new HeapAutoRecoveryManager();
        try {
            AtomicBoolean hookExecuted = new AtomicBoolean(false);
            manager.registerRecoveryHook("EmergencyClearer", () -> hookExecuted.set(true));

            OutOfMemoryError dummyOOM = new OutOfMemoryError("Java heap space simulation");
            HeapAutoRecoveryManager.AutoRecoveryIncident incident = manager.handleTrappedOOM("HTTP: /api/graph/all", dummyOOM);

            assertNotNull(incident, "Emergency incident recorded");
            assertTrue(hookExecuted.get(), "Emergency hook executed");
            assertTrue(incident.trigger.contains("TRAPPED_OOM: HTTP: /api/graph/all"), "Trigger source recorded");
            assertNotNull(incident.timestampFormatted, "Formatted timestamp present");
        } finally {
            manager.stopWatchdog();
        }
    }

    public void testSimulateMemoryPressure() {
        HeapAutoRecoveryManager manager = new HeapAutoRecoveryManager();
        try {
            AtomicBoolean cacheEvicted = new AtomicBoolean(false);
            manager.registerRecoveryHook("SimEviction", () -> cacheEvicted.set(true));

            Map<String, Object> simResult = manager.simulateMemoryPressure(20);
            assertNotNull(simResult, "Simulation result returned");
            assertTrue((Boolean) simResult.get("success"), "Simulation success");
            assertTrue(cacheEvicted.get(), "Simulated eviction executed");

            @SuppressWarnings("unchecked")
            Map<String, Object> incidentMap = (Map<String, Object>) simResult.get("incident");
            assertNotNull(incidentMap, "Simulation incident returned");
            assertTrue(incidentMap.get("trigger").toString().contains("SIMULATION"), "Simulation trigger logged");
        } finally {
            manager.stopWatchdog();
        }
    }

    public void testCircuitBreakerReset() {
        HeapAutoRecoveryManager manager = new HeapAutoRecoveryManager();
        try {
            AtomicBoolean resetCalled = new AtomicBoolean(false);
            manager.setOnCircuitBreakerReset(() -> resetCalled.set(true));

            // Trip circuit breaker explicitly
            manager.tripCircuitBreaker("Test emergency memory pressure");
            assertTrue(manager.isCircuitBreakerActive(), "Circuit breaker should be active after trip");

            // Reset manually
            manager.resetCircuitBreaker();
            assertFalse(manager.isCircuitBreakerActive(), "Circuit breaker should be closed after reset");
            assertTrue(resetCalled.get(), "Reset callback should be called");
        } finally {
            manager.stopWatchdog();
        }
    }
}
