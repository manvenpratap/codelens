package com.codelens.analysis;

import com.codelens.core.model.*;
import java.util.ArrayList;
import java.util.Collections;
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

    public void testSameNamedMethodsInDifferentClassesCallGraphIndependence() {
        CallGraphAnalyzer analyzer = new CallGraphAnalyzer();

        String orderExecute = "com.example.service.OrderService.execute()";
        String batchExecute = "com.example.batch.BatchProcessor.execute()";
        String runnerRun    = "com.example.app.AppRunner.run()";
        String ambigCaller  = "com.example.app.OtherCaller.callUnknown()";

        List<String> methods = List.of(orderExecute, batchExecute, runnerRun, ambigCaller);

        List<CodeRelationship> rels = new ArrayList<>();

        // Call 1: AppRunner explicitly calls OrderService.execute
        CodeRelationship r1 = new CodeRelationship();
        r1.setFromEntityFqn(runnerRun);
        r1.setToEntityFqn("~com.example.service.OrderService.execute");
        r1.setKind("CALLS");
        rels.add(r1);

        // Call 2: AppRunner calls BatchProcessor.execute via scope hint
        CodeRelationship r2 = new CodeRelationship();
        r2.setFromEntityFqn(runnerRun);
        r2.setToEntityFqn("~batchProcessor.execute");
        r2.setKind("CALLS");
        rels.add(r2);

        // Call 3: Ambiguous caller calls execute on unknown receiver 'x' (must NOT attach to either class)
        CodeRelationship r3 = new CodeRelationship();
        r3.setFromEntityFqn(ambigCaller);
        r3.setToEntityFqn("~x.execute");
        r3.setKind("CALLS");
        rels.add(r3);

        analyzer.rebuild(methods, rels);

        org.jgrapht.Graph<String, org.jgrapht.graph.DefaultEdge> g = analyzer.getCallGraph();

        // OrderService.execute should have exactly 1 caller (AppRunner.run)
        assertEquals(1, analyzer.callerCount(orderExecute), "OrderService.execute caller count");
        assertTrue(g.containsEdge(runnerRun, orderExecute), "OrderService.execute caller is runnerRun");

        // BatchProcessor.execute should have exactly 1 caller (AppRunner.run)
        assertEquals(1, analyzer.callerCount(batchExecute), "BatchProcessor.execute caller count");
        assertTrue(g.containsEdge(runnerRun, batchExecute), "BatchProcessor.execute caller is runnerRun");

        // Ambiguous call must NOT attach to OrderService or BatchProcessor
        assertFalse(g.containsEdge(ambigCaller, orderExecute), "OrderService.execute should NOT receive ambiguous call");
        assertFalse(g.containsEdge(ambigCaller, batchExecute), "BatchProcessor.execute should NOT receive ambiguous call");
    }

    public void testInconsistencyDetectorDistinguishesClasses() {
        InconsistencyDetector detector = new InconsistencyDetector();

        // 1. Two unrelated classes with same method name but divergent signatures
        CodeMethod mOrderExec = new CodeMethod();
        mOrderExec.setFqn("com.example.service.OrderService.execute(Order)");
        mOrderExec.setSimpleName("execute");
        mOrderExec.setDeclaringTypeFqn("com.example.service.OrderService");
        mOrderExec.setReturnType("boolean");
        mOrderExec.setParameters(List.of(new MethodParam("Order", "order")));

        CodeMethod mBatchExec = new CodeMethod();
        mBatchExec.setFqn("com.example.batch.BatchProcessor.execute()");
        mBatchExec.setSimpleName("execute");
        mBatchExec.setDeclaringTypeFqn("com.example.batch.BatchProcessor");
        mBatchExec.setReturnType("void");

        // 2. Same class methods with divergent signatures
        CodeMethod mSameClass1 = new CodeMethod();
        mSameClass1.setFqn("com.example.service.OrderService.validate(Order)");
        mSameClass1.setSimpleName("validate");
        mSameClass1.setDeclaringTypeFqn("com.example.service.OrderService");
        mSameClass1.setReturnType("boolean");
        mSameClass1.setParameters(List.of(new MethodParam("Order", "order")));

        CodeMethod mSameClass2 = new CodeMethod();
        mSameClass2.setFqn("com.example.service.OrderService.validate(Order,int)");
        mSameClass2.setSimpleName("validate");
        mSameClass2.setDeclaringTypeFqn("com.example.service.OrderService");
        mSameClass2.setReturnType("void");
        mSameClass2.setParameters(List.of(new MethodParam("Order", "order"), new MethodParam("int", "flags")));

        // 3. Constructors with different parameter counts should never be flagged
        CodeMethod mCtor1 = new CodeMethod();
        mCtor1.setFqn("com.example.service.OrderService.<init>()");
        mCtor1.setSimpleName("<init>");
        mCtor1.setDeclaringTypeFqn("com.example.service.OrderService");

        CodeMethod mCtor2 = new CodeMethod();
        mCtor2.setFqn("com.example.service.OrderService.<init>(String)");
        mCtor2.setSimpleName("<init>");
        mCtor2.setDeclaringTypeFqn("com.example.service.OrderService");
        mCtor2.setParameters(List.of(new MethodParam("String", "name")));

        List<CodeMethod> allMethods = List.of(mOrderExec, mBatchExec, mSameClass1, mSameClass2, mCtor1, mCtor2);
        List<CodeType> allTypes = new ArrayList<>();

        CodeType tOrder = new CodeType();
        tOrder.setFqn("com.example.service.OrderService");
        tOrder.setSimpleName("OrderService");
        allTypes.add(tOrder);

        CodeType tBatch = new CodeType();
        tBatch.setFqn("com.example.batch.BatchProcessor");
        tBatch.setSimpleName("BatchProcessor");
        allTypes.add(tBatch);

        List<InconsistencyReport> reports = detector.detect(allTypes, allMethods, Collections.emptyList());

        // Unrelated classes (OrderService vs BatchProcessor) must NOT be flagged
        boolean falsePositive = reports.stream().anyMatch(r ->
            "DIVERGENT_SIGNATURE".equals(r.getKind()) &&
            (r.getEntity1Fqn().contains("BatchProcessor") || r.getEntity2Fqn().contains("BatchProcessor"))
        );
        assertFalse(falsePositive, "Unrelated classes sharing method name 'execute' should NOT be flagged as divergent signature");

        // Constructors must never be flagged
        boolean ctorFlagged = reports.stream().anyMatch(r ->
            "DIVERGENT_SIGNATURE".equals(r.getKind()) &&
            (r.getEntity1Fqn().contains("<init>") || r.getEntity2Fqn().contains("<init>"))
        );
        assertFalse(ctorFlagged, "Constructors must NEVER be flagged as divergent signature");

        // Same class divergent signature MUST be flagged
        boolean sameClassFlagged = reports.stream().anyMatch(r ->
            "DIVERGENT_SIGNATURE".equals(r.getKind()) &&
            r.getEntity1Fqn().contains("validate") && r.getEntity2Fqn().contains("validate")
        );
        assertTrue(sameClassFlagged, "Same class methods with divergent signature MUST be flagged");
    }

    public void testDeadCodeDetectionTreatsSameNamedMethodsSeparately() {
        CallGraphAnalyzer analyzer = new CallGraphAnalyzer();
        FieldImpactAnalyzer fieldImpact = new FieldImpactAnalyzer();
        CodeReviewEngine reviewEngine = new CodeReviewEngine();
        ReportService reportService = new ReportService(analyzer, fieldImpact, reviewEngine);

        String orderExecute = "com.example.service.OrderService.execute()";
        String batchExecute = "com.example.batch.BatchProcessor.execute()";
        String caller = "com.example.app.Runner.run()";

        List<CodeType> types = new ArrayList<>();
        CodeType t1 = new CodeType();
        t1.setFqn("com.example.service.OrderService");
        t1.setLineCount(100);
        types.add(t1);

        CodeType t2 = new CodeType();
        t2.setFqn("com.example.batch.BatchProcessor");
        t2.setLineCount(100);
        types.add(t2);

        List<CodeMethod> methods = new ArrayList<>();
        CodeMethod m1 = new CodeMethod();
        m1.setFqn(orderExecute);
        m1.setSimpleName("execute");
        m1.setDeclaringTypeFqn("com.example.service.OrderService");
        m1.setStartLine(10);
        m1.setEndLine(20);
        methods.add(m1);

        CodeMethod m2 = new CodeMethod();
        m2.setFqn(batchExecute);
        m2.setSimpleName("execute");
        m2.setDeclaringTypeFqn("com.example.batch.BatchProcessor");
        m2.setStartLine(30);
        m2.setEndLine(45);
        methods.add(m2);

        // Only OrderService.execute is called by Runner
        List<CodeRelationship> rels = new ArrayList<>();
        CodeRelationship r = new CodeRelationship();
        r.setFromEntityFqn(caller);
        r.setToEntityFqn(orderExecute);
        r.setKind("CALLS");
        rels.add(r);

        analyzer.rebuild(List.of(orderExecute, batchExecute, caller), rels);

        ReportService.DeadCodeReportData deadData = reportService.buildDeadCodeData(types, methods, Collections.emptyList(), rels);

        // BatchProcessor.execute should be dead code (0 callers)
        boolean batchIsDead = deadData.orphanedMethods.stream()
            .anyMatch(i -> i.methodFqn.equals(batchExecute));
        assertTrue(batchIsDead, "BatchProcessor.execute should be flagged as orphaned/dead code");

        // OrderService.execute has a caller, so it should NOT be dead code
        boolean orderIsDead = deadData.orphanedMethods.stream()
            .anyMatch(i -> i.methodFqn.equals(orderExecute));
        assertFalse(orderIsDead, "OrderService.execute should NOT be dead code since it has a caller");
    }

    public void testWriteInteractiveHtmlSnapshotStreamingAndEscaping() throws Exception {
        ReportService reportService = new ReportService(new CallGraphAnalyzer(), new FieldImpactAnalyzer(), new CodeReviewEngine());
        java.io.StringWriter sw = new java.io.StringWriter();

        // Data containing </script> inside a label or string to verify proper escaping
        java.util.Map<String, Object> maliciousGraph = java.util.Map.of(
            "label", "Test</script><script>alert(1)</script>",
            "nodes", java.util.List.of(java.util.Map.of("id", "n1", "name", "ClassWith</script>Tag"))
        );

        ReportService.ArchitectureReportData archData = new ReportService.ArchitectureReportData();
        archData.generatedAt = "2026-10-02 12:00:00";

        reportService.writeInteractiveHtmlSnapshot(sw, "SecurityTestProject", maliciousGraph, maliciousGraph, archData);
        String html = sw.toString();

        assertNotNull(html, "Generated HTML snapshot should not be null");
        assertTrue(html.contains("<!DOCTYPE html>"), "HTML should contain DOCTYPE");
        assertTrue(html.contains("SecurityTestProject"), "HTML should contain project name");
        assertTrue(html.contains("<\\/script>"), "HTML should escape </script> to <\\/script> in embedded JSON");
        // Verify that raw unescaped </script> inside JSON is NOT present before closing tags
        assertTrue(!html.contains("ClassWith</script>Tag"), "Raw unescaped </script> must not appear in JSON payload");
    }

    public void testExecutiveSummaryPrecomputedDataReuse() {
        ReportService reportService = new ReportService(new CallGraphAnalyzer(), new FieldImpactAnalyzer(), new CodeReviewEngine());

        List<CodeType> types = new ArrayList<>();
        CodeType t1 = new CodeType();
        t1.setFqn("com.example.Service");
        t1.setSimpleName("Service");
        t1.setPackageFqn("com.example");
        types.add(t1);

        ReportService.ArchitectureReportData cachedArch = new ReportService.ArchitectureReportData();
        cachedArch.healthScore = 95;
        cachedArch.totalPackages = 1;
        cachedArch.totalDependencies = 0;

        ReportService.ChangeRiskReportData cachedRisk = new ReportService.ChangeRiskReportData();
        cachedRisk.averageRiskScore = 15;
        cachedRisk.criticalRiskCount = 0;

        ReportService.CircularDependencyReportData cachedCycles = new ReportService.CircularDependencyReportData();
        cachedCycles.acyclicScore = 100;
        cachedCycles.totalClassCycles = 0;

        ReportService.ArchetypeGovernanceReportData cachedGov = new ReportService.ArchetypeGovernanceReportData();
        cachedGov.governanceScore = 90;
        cachedGov.totalViolations = 0;

        ReportService.DeadCodeReportData cachedDead = new ReportService.DeadCodeReportData();
        cachedDead.deadCodePercentage = 5.0;
        cachedDead.orphanedMethodsCount = 1;

        ReportService.TechnicalDebtReportData cachedDebt = new ReportService.TechnicalDebtReportData();
        cachedDebt.maintainabilityScore = 88;
        cachedDebt.totalDebtHours = 4.0;
        cachedDebt.sqaleRating = "A";

        // Call the overloaded method passing cached reports
        ReportService.ExecutiveSummaryReportData summary = reportService.buildExecutiveSummaryData(
            types, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            cachedArch, cachedRisk, cachedCycles, cachedGov, cachedDead, cachedDebt
        );

        assertNotNull(summary, "Executive summary should not be null");
        assertTrue(summary.overallHealthScore >= 85, "Overall health score should reflect high cached dimension scores");
        assertTrue(summary.overallGrade.startsWith("A"), "Overall grade should be A tier");
        assertEquals(6, summary.dimensions.size(), "Should have exactly 6 evaluated dimensions");
    }

    public void testChangeRiskFieldLookupOptimization() {
        ReportService reportService = new ReportService(new CallGraphAnalyzer(), new FieldImpactAnalyzer(), new CodeReviewEngine());

        List<CodeType> types = new ArrayList<>();
        List<CodeField> fields = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            String cFqn = "com.example.Class" + i;
            CodeType t = new CodeType();
            t.setFqn(cFqn);
            t.setSimpleName("Class" + i);
            t.setPackageFqn("com.example");
            types.add(t);

            for (int j = 0; j < 10; j++) {
                CodeField f = new CodeField();
                f.setFqn(cFqn + ".field" + j);
                f.setDeclaringTypeFqn(cFqn);
                fields.add(f);
            }
        }

        ReportService.ChangeRiskReportData risk = reportService.buildChangeRiskData(
            types, Collections.emptyList(), fields, Collections.emptyList(), Collections.emptyList()
        );

        assertNotNull(risk, "Change risk data should not be null");
        assertEquals(50, risk.totalClassesAnalyzed, "All 50 classes should be analyzed");
        assertEquals(50, risk.classRiskRankings.size(), "All 50 classes should have evaluated risk");
    }

    public void testHtmlReportsPaginationEmbedded() {
        CallGraphAnalyzer analyzer = new CallGraphAnalyzer();
        FieldImpactAnalyzer fieldImpact = new FieldImpactAnalyzer();
        CodeReviewEngine reviewEngine = new CodeReviewEngine();
        ReportService reportService = new ReportService(analyzer, fieldImpact, reviewEngine);

        // 1. Architecture
        ReportService.ArchitectureReportData arch = new ReportService.ArchitectureReportData();
        String archHtml = reportService.renderArchitectureHtml(arch);
        assertTrue(archHtml.contains("report-html-pagination"), "Architecture HTML should contain pagination");
        assertTrue(archHtml.contains("report-html-size-select"), "Architecture HTML should contain size select");

        // 2. Review
        ReportService.ReviewReportData rev = new ReportService.ReviewReportData();
        String revHtml = reportService.renderReviewHtml(rev);
        assertTrue(revHtml.contains("report-html-pagination"), "Review HTML should contain pagination");

        // 3. Metrics
        ReportService.MetricsReportData met = new ReportService.MetricsReportData();
        String metHtml = reportService.renderMetricsHtml(met);
        assertTrue(metHtml.contains("report-html-pagination"), "Metrics HTML should contain pagination");

        // 4. Change Risk
        ReportService.ChangeRiskReportData risk = new ReportService.ChangeRiskReportData();
        String riskHtml = reportService.renderChangeRiskHtml(risk);
        assertTrue(riskHtml.contains("report-html-pagination"), "ChangeRisk HTML should contain pagination");

        // 5. Dead Code
        ReportService.DeadCodeReportData dead = new ReportService.DeadCodeReportData();
        String deadHtml = reportService.renderDeadCodeHtml(dead);
        assertTrue(deadHtml.contains("report-html-pagination"), "DeadCode HTML should contain pagination");

        // 6. Circular Dependency
        ReportService.CircularDependencyReportData circ = new ReportService.CircularDependencyReportData();
        String circHtml = reportService.renderCircularDependencyHtml(circ);
        assertTrue(circHtml.contains("report-html-pagination"), "CircularDependency HTML should contain pagination");

        // 7. Archetype Governance
        ReportService.ArchetypeGovernanceReportData gov = new ReportService.ArchetypeGovernanceReportData();
        String govHtml = reportService.renderArchetypeGovernanceHtml(gov);
        assertTrue(govHtml.contains("report-html-pagination"), "Governance HTML should contain pagination");

        // 8. Technical Debt
        ReportService.TechnicalDebtReportData debt = new ReportService.TechnicalDebtReportData();
        debt.sqaleRating = "A";
        debt.maintainabilityScore = 90;
        debt.totalDebtHours = 5.0;
        debt.totalDebtDays = 0.6;
        debt.debtRatioPercent = 2.1;
        String debtHtml = reportService.renderTechnicalDebtHtml(debt);
        assertTrue(debtHtml.contains("report-html-pagination"), "TechnicalDebt HTML should contain pagination");

        // 9. Executive Summary
        ReportService.ExecutiveSummaryReportData exec = new ReportService.ExecutiveSummaryReportData();
        String execHtml = reportService.renderExecutiveSummaryHtml(exec);
        assertTrue(execHtml.contains("report-html-pagination"), "ExecutiveSummary HTML should contain pagination");

        // 10. API Catalog
        ReportService.ApiCatalogReportData apiCat = new ReportService.ApiCatalogReportData();
        String apiHtml = reportService.renderApiCatalogHtml(apiCat);
        assertTrue(apiHtml.contains("report-html-pagination"), "ApiCatalog HTML should contain pagination");

        // 11. Database Access
        ReportService.DatabaseAccessReportData dbAcc = new ReportService.DatabaseAccessReportData();
        String dbHtml = reportService.renderDatabaseAccessHtml(dbAcc);
        assertTrue(dbHtml.contains("report-html-pagination"), "DatabaseAccess HTML should contain pagination");

        // 12. Concurrency Audit
        ReportService.ConcurrencyAuditReportData conc = new ReportService.ConcurrencyAuditReportData();
        String concHtml = reportService.renderConcurrencyAuditHtml(conc);
        assertTrue(concHtml.contains("report-html-pagination"), "ConcurrencyAudit HTML should contain pagination");

        // 13. Module Coupling Insights
        ReportService.ModuleCouplingReportData modCoupling = new ReportService.ModuleCouplingReportData();
        String modHtml = reportService.renderModuleCouplingHtml(modCoupling);
        assertTrue(modHtml.contains("report-html-pagination"), "ModuleCoupling HTML should contain pagination");
    }

    public void testModuleCouplingInsights() {
        CallGraphAnalyzer analyzer = new CallGraphAnalyzer();
        FieldImpactAnalyzer fieldImpact = new FieldImpactAnalyzer();
        CodeReviewEngine reviewEngine = new CodeReviewEngine();
        ReportService reportService = new ReportService(analyzer, fieldImpact, reviewEngine);

        List<CodeType> types = new ArrayList<>();
        CodeType t1 = new CodeType();
        t1.setFqn("com.example.service.OrderService");
        t1.setSimpleName("OrderService");
        t1.setPackageFqn("com.example.service");
        t1.setKind("CLASS");
        types.add(t1);

        CodeType t2 = new CodeType();
        t2.setFqn("com.example.model.Order");
        t2.setSimpleName("Order");
        t2.setPackageFqn("com.example.model");
        t2.setKind("CLASS");
        types.add(t2);

        CodeType t3 = new CodeType();
        t3.setFqn("com.example.model.OrderContract");
        t3.setSimpleName("OrderContract");
        t3.setPackageFqn("com.example.model");
        t3.setKind("INTERFACE");
        types.add(t3);

        List<CodeMethod> methods = new ArrayList<>();
        CodeMethod m1 = new CodeMethod();
        m1.setFqn("com.example.service.OrderService.create");
        m1.setDeclaringTypeFqn("com.example.service.OrderService");
        methods.add(m1);

        CodeMethod m2 = new CodeMethod();
        m2.setFqn("com.example.model.Order.getId");
        m2.setDeclaringTypeFqn("com.example.model.Order");
        methods.add(m2);

        List<CodeRelationship> rels = new ArrayList<>();
        CodeRelationship r1 = new CodeRelationship();
        r1.setFromEntityFqn("com.example.service.OrderService.create");
        r1.setToEntityFqn("com.example.model.Order.getId");
        r1.setKind("CALLS");
        rels.add(r1);

        // Add reverse call to create a bidirectional tangle
        CodeRelationship r2 = new CodeRelationship();
        r2.setFromEntityFqn("com.example.model.Order.getId");
        r2.setToEntityFqn("com.example.service.OrderService.create");
        r2.setKind("CALLS");
        rels.add(r2);

        ReportService.ModuleCouplingReportData data = reportService.buildModuleCouplingData(types, methods, Collections.emptyList(), rels);

        assertNotNull(data, "ModuleCouplingReportData not null");
        assertEquals(2, data.totalModules, "Total modules should be 2");
        assertEquals(2, data.totalCrossModuleRelationships, "Total cross relationships should be 2");
        assertEquals(1, data.bidirectionalTanglesCount, "Should detect 1 bidirectional tangle");
        assertTrue(data.decouplingScore > 0, "Decoupling score should be positive");

        // Verify multi-format exports
        String json = reportService.renderModuleCouplingJson(data);
        assertTrue(json.contains("\"totalModules\" : 2"), "JSON export should contain totalModules");

        String html = reportService.renderModuleCouplingHtml(data);
        assertTrue(html.contains("report-html-pagination"), "HTML export should contain pagination");
        assertTrue(html.contains("Module Coupling &amp; Stability Insights"), "HTML should contain report title");

        String md = reportService.renderModuleCouplingMarkdown(data);
        assertTrue(md.contains("# 📦 CodeLens Module Coupling & Stability Insights Report"), "Markdown should contain header");
        assertTrue(md.contains("com.example.service"), "Markdown should contain module name");

        String csv = reportService.renderModuleCouplingCsv(data);
        assertTrue(csv.contains("MODULE,com.example.service"), "CSV should contain module row");
        assertTrue(csv.contains("PAIR,com.example.service,com.example.model"), "CSV should contain pair row");
    }
}
