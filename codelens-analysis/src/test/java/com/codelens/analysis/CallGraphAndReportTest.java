package com.codelens.analysis;

import com.codelens.core.model.*;
import java.util.ArrayList;
import java.util.List;

public class CallGraphAndReportTest {

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertFalse(boolean condition, String msg) {
        if (condition) throw new AssertionError("Assertion failed (expected false): " + msg);
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + " (expected: " + expected + ", got: " + actual + ")");
    }

    private static void assertNotNull(Object obj, String msg) {
        if (obj == null) throw new AssertionError("Assertion failed (expected non-null): " + msg);
    }

    public void testCallGraphRebuildAndViews() {
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

        CodeRelationship r2 = new CodeRelationship();
        r2.setFromEntityFqn("com.tcs.bancs.BS.BatchService.BSPSProcess");
        r2.setToEntityFqn("com.tcs.bancs.AM.AMDGAccountGrabber.grabDetails");
        r2.setKind("CALLS");
        rels.add(r2);

        analyzer.rebuild(methodFqns, rels);

        CallGraphAnalyzer.GraphView fullView = analyzer.fullGraphView();
        assertNotNull(fullView, "fullView not null");
        assertFalse(fullView.nodes.isEmpty(), "fullView nodes not empty");
        assertEquals(4, fullView.nodes.size(), "fullView nodes count");
        assertEquals(2, fullView.edges.size(), "fullView edges count");

        CallGraphAnalyzer.GraphView archView = analyzer.architectureGraphView("classes", null);
        assertNotNull(archView, "archView not null");
        assertFalse(archView.nodes.isEmpty(), "archView nodes not empty");
    }

    public void testGenerateInteractiveHtmlSnapshot() {
        CallGraphAnalyzer analyzer = new CallGraphAnalyzer();
        FieldImpactAnalyzer fieldImpact = new FieldImpactAnalyzer();
        CodeReviewEngine reviewEngine = new CodeReviewEngine();
        ReportService reportService = new ReportService(analyzer, fieldImpact, reviewEngine);

        List<CodeType> types = new ArrayList<>();
        CodeType type1 = new CodeType();
        type1.setFqn("com.tcs.bancs.AM.AccountService");
        type1.setSimpleName("AccountService");
        type1.setPackageFqn("com.tcs.bancs.AM");
        type1.setKind("CLASS");
        type1.setLineCount(150);
        types.add(type1);

        CodeType type2 = new CodeType();
        type2.setFqn("com.tcs.bancs.BS.BatchService");
        type2.setSimpleName("BatchService");
        type2.setPackageFqn("com.tcs.bancs.BS");
        type2.setKind("CLASS");
        type2.setLineCount(300);
        types.add(type2);

        List<CodeMethod> methods = new ArrayList<>();
        CodeMethod m1 = new CodeMethod();
        m1.setFqn("com.tcs.bancs.AM.AccountService.AMETFetchBalance");
        m1.setSimpleName("AMETFetchBalance");
        m1.setDeclaringTypeFqn("com.tcs.bancs.AM.AccountService");
        methods.add(m1);

        List<CodeField> fields = new ArrayList<>();
        List<CodeRelationship> rels = new ArrayList<>();
        CodeRelationship r = new CodeRelationship();
        r.setFromEntityFqn("com.tcs.bancs.AM.AccountService.AMETFetchBalance");
        r.setToEntityFqn("com.tcs.bancs.BS.BatchService.BSPSProcess");
        r.setKind("CALLS");
        rels.add(r);

        analyzer.rebuild(List.of(m1.getFqn()), rels);

        ReportService.ArchitectureReportData archData = reportService.buildArchitectureData(types, methods, fields, rels);
        CallGraphAnalyzer.GraphView fullGraph = analyzer.fullGraphView();
        CallGraphAnalyzer.GraphView archGraph = analyzer.architectureGraphView("classes", null);

        String html = reportService.generateInteractiveHtmlSnapshot("BaNCS Module AM", fullGraph, archGraph, archData);
        assertNotNull(html, "HTML snapshot should not be null");
        assertTrue(html.contains("<!DOCTYPE html>"), "Should contain DOCTYPE");
        assertTrue(html.contains("BaNCS Module AM"), "Should contain project name");
        assertTrue(html.contains("codelens-fullgraph"), "Should embed full graph json");
        assertTrue(html.contains("codelens-archgraph"), "Should embed arch graph json");
        assertTrue(html.contains("Interactive Graph Snapshot"), "Should contain header title");
        assertTrue(html.contains("canvas id=\"graph-canvas\""), "Should contain canvas");
    }
}
