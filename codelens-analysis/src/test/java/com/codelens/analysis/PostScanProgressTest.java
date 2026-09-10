package com.codelens.analysis;

import com.codelens.core.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class PostScanProgressTest {

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertFalse(boolean condition, String msg) {
        if (condition) throw new AssertionError("Assertion failed (expected false): " + msg);
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + " (expected: " + expected + ", got: " + actual + ")");
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(msg + " (expected: " + expected + ", got: " + actual + ")");
    }

    private static void assertNotNull(Object obj, String msg) {
        if (obj == null) throw new AssertionError("Assertion failed (expected non-null): " + msg);
    }

    public void testScanProgressStageTransitionsAndPercentages() {
        ScanProgress progress = new ScanProgress();
        assertEquals("IDLE", progress.getActiveStage(), "Initial stage should be IDLE");
        assertEquals(0, progress.getPercentage(), "Initial percentage should be 0");

        // 1. Prepare stage
        progress.setActiveStage("PREPARE");
        progress.setPercentage(2);
        progress.setCurrentPhase("Preparing workspace");
        progress.setCurrentDetail("Discovering Java source files");
        assertEquals("PREPARE", progress.getActiveStage(), "Stage should be PREPARE");
        assertEquals(2, progress.getPercentage(), "Percentage should be 2");

        // 2. Parse stage
        progress.setActiveStage("PARSE");
        progress.setProcessedFiles(50);
        progress.setTotalFiles(100);
        progress.setPercentage(36);
        assertEquals("PARSE", progress.getActiveStage(), "Stage should be PARSE");
        assertEquals(36, progress.getPercentage(), "Explicit percentage takes precedence");

        // 3. Index stage
        progress.setActiveStage("INDEX");
        progress.setPercentage(72);
        progress.setCurrentPhase("Full-Text & Relational Indexing");
        progress.setCurrentDetail("Committing Lucene index");
        assertEquals("INDEX", progress.getActiveStage(), "Stage should be INDEX");
        assertEquals(72, progress.getPercentage(), "Percentage should be 72");

        // 4. Graph stage
        progress.setActiveStage("GRAPH");
        progress.setPercentage(85);
        progress.setCurrentPhase("Deep Analysis: Call Graph & Field Impact");
        progress.setCurrentDetail("Field Impact: 4,000 of 8,000 relations mapped");
        assertEquals("GRAPH", progress.getActiveStage(), "Stage should be GRAPH");
        assertEquals(85, progress.getPercentage(), "Percentage should be 85");

        // 5. Layout stage
        progress.setActiveStage("LAYOUT");
        progress.setPercentage(95);
        progress.setCurrentPhase("Graph Layout Precomputation");
        progress.setCurrentDetail("Precomputing Sunflower Layout: Full Call Graph (4/6)");
        assertEquals("LAYOUT", progress.getActiveStage(), "Stage should be LAYOUT");
        assertEquals(95, progress.getPercentage(), "Percentage should be 95");

        // 6. Complete stage
        progress.setActiveStage("COMPLETE");
        progress.setPercentage(100);
        progress.setStatus(ScanProgress.Status.COMPLETE);
        assertEquals("COMPLETE", progress.getActiveStage(), "Stage should be COMPLETE");
        assertEquals(100, progress.getPercentage(), "Percentage should be 100");
    }

    public void testCallGraphAnalyzerProgressReporting() {
        CallGraphAnalyzer analyzer = new CallGraphAnalyzer();

        List<String> methodFqns = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            methodFqns.add("com.example.Service" + (i / 10) + ".method" + i);
        }

        List<CodeRelationship> rels = new ArrayList<>();
        for (int i = 0; i < 4500; i++) {
            CodeRelationship r = new CodeRelationship();
            r.setFromEntityFqn(methodFqns.get(i % methodFqns.size()));
            r.setToEntityFqn(methodFqns.get((i + 1) % methodFqns.size()));
            r.setKind("CALLS");
            rels.add(r);
        }

        AtomicInteger callbackCount = new AtomicInteger(0);
        List<String> reportedPhases = Collections.synchronizedList(new ArrayList<>());
        List<String> reportedDetails = Collections.synchronizedList(new ArrayList<>());

        analyzer.rebuild(methodFqns, rels, (phase, current, total, detail) -> {
            callbackCount.incrementAndGet();
            reportedPhases.add(phase);
            reportedDetails.add(detail);
        });

        assertTrue(callbackCount.get() > 0, "Progress callback must be called at least once");
        assertTrue(reportedPhases.contains("Call Graph: Indexing Methods"), "Must report vertex indexing step");
        assertTrue(reportedPhases.contains("Call Graph: Mapping Edges"), "Must report edge resolution step");

        CallGraphAnalyzer.GraphView view = analyzer.fullGraphView();
        assertNotNull(view, "View should not be null");
        assertFalse(view.nodes.isEmpty(), "Nodes should not be empty");
    }

    public void testFieldImpactAnalyzerProgressReporting() {
        FieldImpactAnalyzer analyzer = new FieldImpactAnalyzer();

        List<CodeRelationship> rels = new ArrayList<>();
        Set<String> knownCallers = new HashSet<>();

        for (int i = 0; i < 5000; i++) {
            CodeRelationship r = new CodeRelationship();
            String callerFqn = "com.example.Service" + (i / 50) + ".caller" + i;
            String fieldFqn = "com.example.Entity" + (i / 100) + ".field" + (i % 20);
            r.setFromEntityFqn(callerFqn);
            r.setToEntityFqn(fieldFqn);
            r.setKind(i % 2 == 0 ? "READS_FIELD" : "WRITES_FIELD");
            rels.add(r);
            knownCallers.add(callerFqn);
        }

        AtomicInteger callbackCount = new AtomicInteger(0);
        List<String> reportedPhases = Collections.synchronizedList(new ArrayList<>());

        analyzer.rebuild(rels, knownCallers, (phase, current, total, detail) -> {
            callbackCount.incrementAndGet();
            reportedPhases.add(phase);
        });

        assertTrue(callbackCount.get() > 0, "FieldImpactAnalyzer callback must be called");
        assertTrue(reportedPhases.contains("Field Impact: Indexing Relations"), "Must report indexing step");

        // Verify analysis works with indexed fast lookup
        FieldImpactAnalyzer.ImpactView impact = analyzer.analyse("com.example.Entity0.field0");
        assertNotNull(impact, "Impact result should not be null");
    }

    public void testLayoutWarmupSequentialPrecomputation() {
        CallGraphAnalyzer analyzer = new CallGraphAnalyzer();

        List<String> methodFqns = List.of(
            "com.tcs.bancs.AM.AccountService.AMETFetchBalance",
            "com.tcs.bancs.AM.AccountService.AMBTTransferFunds",
            "com.tcs.bancs.AM.AMDGAccountGrabber.grabDetails",
            "com.tcs.bancs.BS.BatchService.BSPSProcess"
        );

        List<CodeRelationship> rels = new ArrayList<>();
        CodeRelationship r1 = new CodeRelationship();
        r1.setFromEntityFqn("com.tcs.bancs.AM.AccountService.AMBTTransferFunds");
        r1.setToEntityFqn("com.tcs.bancs.AM.AccountService.AMETFetchBalance");
        r1.setKind("CALLS");
        rels.add(r1);

        analyzer.rebuild(methodFqns, rels);

        // Test the 6 layout views that warmupGraphCache generates:
        // 1. Architecture classes
        CallGraphAnalyzer.GraphView v1 = analyzer.precomputedArchitectureGraphView("classes", null);
        assertNotNull(v1, "v1 classes layout should exist");
        // 2. Architecture methods
        CallGraphAnalyzer.GraphView v2 = analyzer.precomputedArchitectureGraphView("methods", null);
        assertNotNull(v2, "v2 methods layout should exist");
        // 3. Full graph (regular)
        CallGraphAnalyzer.GraphView v3 = analyzer.precomputedFullGraphView(false);
        assertNotNull(v3, "v3 full graph layout should exist");
        // 4. Full graph (hide getters)
        CallGraphAnalyzer.GraphView v4 = analyzer.precomputedFullGraphView(true);
        assertNotNull(v4, "v4 full graph (no getters) should exist");
        // 5. Standard full graph
        CallGraphAnalyzer.GraphView v5 = analyzer.fullGraphView();
        assertNotNull(v5, "v5 full graph view should exist");
        // 6. Standard classes view
        CallGraphAnalyzer.GraphView v6 = analyzer.architectureGraphView("classes", null);
        assertNotNull(v6, "v6 arch classes view should exist");

        for (CallGraphAnalyzer.GraphNode n : v1.nodes) {
            assertNotNull(n.x, "Node x coordinate must be non-null");
            assertNotNull(n.y, "Node y coordinate must be non-null");
        }
    }

    public void testScanProgressSubProgressAndDynamicMetrics() {
        ScanProgress p = new ScanProgress();
        p.setSubProgress(7, 10, "idx_rels_from");
        assertEquals(7, p.getStageCurrent(), "stageCurrent should be 7");
        assertEquals(10, p.getStageTotal(), "stageTotal should be 10");
        assertEquals("idx_rels_from", p.getStageItem(), "stageItem should match");

        p.setDynamicMetrics("Lucene Docs", "28,450", "DB Indexes", "7 / 10", "Target Table", "relationships", "Rows", "142,800");
        assertEquals("Lucene Docs", p.getMetric1Label(), "metric1Label");
        assertEquals("28,450", p.getMetric1Value(), "metric1Value");
        assertEquals("DB Indexes", p.getMetric2Label(), "metric2Label");
        assertEquals("7 / 10", p.getMetric2Value(), "metric2Value");
        assertEquals("Target Table", p.getMetric3Label(), "metric3Label");
        assertEquals("relationships", p.getMetric3Value(), "metric3Value");
        assertEquals("Rows", p.getMetric4Label(), "metric4Label");
        assertEquals("142,800", p.getMetric4Value(), "metric4Value");
    }

    public void testCallGraphAnalyzerLayoutProgressListener() {
        CallGraphAnalyzer analyzer = new CallGraphAnalyzer();

        List<String> methodFqns = List.of(
            "com.tcs.bancs.AM.AccountService.AMETFetchBalance",
            "com.tcs.bancs.AM.AccountService.AMBTTransferFunds",
            "com.tcs.bancs.PM.PaymentService.PMBTProcess",
            "com.tcs.bancs.TR.TradeService.TRBTExecute"
        );

        List<CodeRelationship> rels = new ArrayList<>();
        CodeRelationship r1 = new CodeRelationship();
        r1.setFromEntityFqn("com.tcs.bancs.AM.AccountService.AMBTTransferFunds");
        r1.setToEntityFqn("com.tcs.bancs.PM.PaymentService.PMBTProcess");
        r1.setKind("CALLS");
        rels.add(r1);

        analyzer.rebuild(methodFqns, rels);

        AtomicInteger clusterCallbacks = new AtomicInteger(0);
        CallGraphAnalyzer.GraphView v = analyzer.precomputedFullGraphView(false, (phase, curr, total, detail) -> {
            clusterCallbacks.incrementAndGet();
            assertTrue(phase.contains("Layout"), "Phase should mention Layout");
            assertTrue(detail.contains("Cluster"), "Detail should mention Cluster");
        });

        assertNotNull(v, "View should exist");
        assertTrue(clusterCallbacks.get() > 0, "Layout cluster callbacks must be fired");
    }
}
