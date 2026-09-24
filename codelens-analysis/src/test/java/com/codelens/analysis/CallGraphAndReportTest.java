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

        // Test caller and callee counts
        assertEquals(1, analyzer.callerCount("com.tcs.bancs.AM.AccountService.AMETFetchBalance"), "callerCount for target");
        assertEquals(0, analyzer.calleeCount("com.tcs.bancs.AM.AccountService.AMETFetchBalance"), "calleeCount for target");
        assertEquals(0, analyzer.callerCount("com.tcs.bancs.AM.AccountService.AMBTTransferFunds"), "callerCount for caller");
        assertEquals(1, analyzer.calleeCount("com.tcs.bancs.AM.AccountService.AMBTTransferFunds"), "calleeCount for caller");
        assertEquals(0, analyzer.callerCount("com.nonexistent.Method"), "callerCount for nonexistent");
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

    public void testPrecomputedGraphLayout() {
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

        CallGraphAnalyzer.GraphView precomputedFull = analyzer.precomputedFullGraphView(false);
        assertNotNull(precomputedFull, "precomputedFull should not be null");
        assertFalse(precomputedFull.nodes.isEmpty(), "precomputedFull nodes not empty");
        for (CallGraphAnalyzer.GraphNode node : precomputedFull.nodes) {
            assertNotNull(node.x, "node x coordinate should not be null: " + node.id);
            assertNotNull(node.y, "node y coordinate should not be null: " + node.id);
            assertFalse(Double.isNaN(node.x), "node x coordinate should not be NaN: " + node.id);
            assertFalse(Double.isNaN(node.y), "node y coordinate should not be NaN: " + node.id);
        }

        CallGraphAnalyzer.GraphView precomputedArch = analyzer.precomputedArchitectureGraphView("classes", null);
        assertNotNull(precomputedArch, "precomputedArch should not be null");
        assertFalse(precomputedArch.nodes.isEmpty(), "precomputedArch nodes not empty");
        for (CallGraphAnalyzer.GraphNode node : precomputedArch.nodes) {
            assertNotNull(node.x, "arch node x coordinate should not be null: " + node.id);
            assertNotNull(node.y, "arch node y coordinate should not be null: " + node.id);
            assertFalse(Double.isNaN(node.x), "arch node x coordinate should not be NaN: " + node.id);
            assertFalse(Double.isNaN(node.y), "arch node y coordinate should not be NaN: " + node.id);
        }

        CallGraphAnalyzer.GraphView hierarchyView = analyzer.callHierarchyView("com.tcs.bancs.AM.AccountService.AMETFetchBalance", 3);
        assertNotNull(hierarchyView, "hierarchyView should not be null");
        assertFalse(hierarchyView.nodes.isEmpty(), "hierarchyView nodes not empty");
        for (CallGraphAnalyzer.GraphNode node : hierarchyView.nodes) {
            assertNotNull(node.x, "hierarchy node x should not be null: " + node.id);
            assertNotNull(node.y, "hierarchy node y should not be null: " + node.id);
            assertFalse(Double.isNaN(node.x), "hierarchy node x should not be NaN: " + node.id);
            assertFalse(Double.isNaN(node.y), "hierarchy node y should not be NaN: " + node.id);
        }
    }

    public void testAnyMethodThisAndDirectInvocationResolution() {
        CallGraphAnalyzer analyzer = new CallGraphAnalyzer();

        // Target methods in a domain service with parameters
        String caller1 = "com.example.banking.LoanService.processApplication(String)";
        String caller2 = "com.example.banking.LoanService.reviewApplication(String)";
        String calleeA = "com.example.banking.LoanService.calculateCreditScore(String,int)";
        String calleeB = "com.example.banking.LoanService.notifyUnderwriter(String,String)";

        List<String> methodFqns = List.of(caller1, caller2, calleeA, calleeB);

        // Caller1 invokes this.calculateCreditScore(...) -> AstVisitor emits "com.example.banking.LoanService.calculateCreditScore"
        // Caller2 invokes notifyUnderwriter(...) directly -> AstVisitor emits "com.example.banking.LoanService.notifyUnderwriter"
        List<CodeRelationship> rels = new ArrayList<>();
        CodeRelationship r1 = new CodeRelationship();
        r1.setFromEntityFqn(caller1);
        r1.setToEntityFqn("com.example.banking.LoanService.calculateCreditScore");
        r1.setKind("CALLS");
        rels.add(r1);

        CodeRelationship r2 = new CodeRelationship();
        r2.setFromEntityFqn(caller2);
        r2.setToEntityFqn("com.example.banking.LoanService.notifyUnderwriter");
        r2.setKind("CALLS");
        rels.add(r2);

        analyzer.rebuild(methodFqns, rels);

        org.jgrapht.Graph<String, org.jgrapht.graph.DefaultEdge> g = analyzer.getCallGraph();
        assertNotNull(g, "Graph should not be null");
        assertTrue(g.containsEdge(caller1, calleeA), "this.calculateCreditScore() should resolve to calculateCreditScore(String,int)");
        assertTrue(g.containsEdge(caller2, calleeB), "notifyUnderwriter() should resolve to notifyUnderwriter(String,String)");
    }

    public void testMastercraftUbiquitousMethodResolution() {
        CallGraphAnalyzer analyzer = new CallGraphAnalyzer();

        // 3 classes in same package com.tcs.bancs.AM, all generated with Get()
        String accGet = "com.tcs.bancs.AM.AccountRecord.Get()";
        String custGet = "com.tcs.bancs.AM.CustomerRecord.Get()";
        String branchGet = "com.tcs.bancs.AM.BranchRecord.Get()";
        String caller1 = "com.tcs.bancs.AM.AccountService.processAccount()";
        String caller2 = "com.tcs.bancs.AM.GenericWorker.doWork()";

        List<String> methods = List.of(accGet, custGet, branchGet, caller1, caller2);

        List<CodeRelationship> rels = new ArrayList<>();
        // Caller 1 calls ~accountRecord.Get - scope hint matches AccountRecord
        CodeRelationship r1 = new CodeRelationship();
        r1.setFromEntityFqn(caller1);
        r1.setToEntityFqn("~accountRecord.Get");
        r1.setKind("CALLS");
        rels.add(r1);

        // Caller 2 calls ~obj.Get - scope hint is generic 'obj' with 3 ambiguous Get() methods in same package
        CodeRelationship r2 = new CodeRelationship();
        r2.setFromEntityFqn(caller2);
        r2.setToEntityFqn("~obj.Get");
        r2.setKind("CALLS");
        rels.add(r2);

        analyzer.rebuild(methods, rels);

        org.jgrapht.Graph<String, org.jgrapht.graph.DefaultEdge> g = analyzer.getCallGraph();
        // Caller 1 should resolve to AccountRecord.Get()
        assertTrue(g.containsEdge(caller1, accGet), "caller1 should resolve to AccountRecord.Get()");
        assertFalse(g.containsEdge(caller1, custGet), "caller1 should not resolve to CustomerRecord.Get()");

        // Caller 2 should NOT resolve to any of them (no false mega-hub)
        assertFalse(g.containsEdge(caller2, accGet), "caller2 should NOT arbitrarily resolve to candidate 0");
        assertFalse(g.containsEdge(caller2, custGet), "caller2 should NOT resolve to candidate 1");
        assertFalse(g.containsEdge(caller2, branchGet), "caller2 should NOT resolve to candidate 2");
    }

    public void testMastercraftPojoFiltering() {
        // Deepcopy, clone, reset, clear should be identified as POJO accessors
        assertTrue(CallGraphAnalyzer.isPojoOrAccessor("com.tcs.bancs.AccountDTO.deepcopy()"), "deepcopy should be pojo");
        assertTrue(CallGraphAnalyzer.isPojoOrAccessor("com.tcs.bancs.AccountDTO.clone()"), "clone should be pojo");
        assertTrue(CallGraphAnalyzer.isPojoOrAccessor("com.tcs.bancs.AccountDTO.reset()"), "reset should be pojo");
        assertTrue(CallGraphAnalyzer.isPojoOrAccessor("com.tcs.bancs.AccountDTO.clear()"), "clear should be pojo");

        // DTO / Buffer lifecycle methods should be identified as POJO accessors
        assertTrue(CallGraphAnalyzer.isPojoOrAccessor("com.tcs.bancs.AccountDTO.Get()"), "DTO Get should be pojo");
        assertTrue(CallGraphAnalyzer.isPojoOrAccessor("com.tcs.bancs.AccountDTO.Create()"), "DTO Create should be pojo");
        assertTrue(CallGraphAnalyzer.isPojoOrAccessor("com.tcs.bancs.BF_PAYMENT.Modify()"), "BF_PAYMENT Modify should be pojo");

        // Real persistent entities should NOT be filtered
        assertFalse(CallGraphAnalyzer.isPojoOrAccessor("com.tcs.bancs.PC_AccountRecord.Get()"), "PC_AccountRecord Get should NOT be filtered");
        assertFalse(CallGraphAnalyzer.isPojoOrAccessor("com.tcs.bancs.PC_AccountRecord.Create()"), "PC_AccountRecord Create should NOT be filtered");
        assertFalse(CallGraphAnalyzer.isPojoOrAccessor("com.tcs.bancs.TradeEntity.Modify()"), "TradeEntity Modify should NOT be filtered");
    }
}
