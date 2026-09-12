package com.codelens.analysis;

import com.codelens.core.model.CodeMethod;
import com.codelens.core.model.CodeRelationship;
import com.codelens.core.model.CodeType;

import java.util.*;

public class CriticalPathAnalyzerTest {

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertFalse(boolean condition, String msg) {
        if (condition) throw new AssertionError("Assertion failed (expected false): " + msg);
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + " (expected: " + expected + ", got: " + actual + ")");
    }

    private static void assertEquals(String expected, String actual, String msg) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(msg + " (expected: \"" + expected + "\", got: \"" + actual + "\")");
    }

    private static void assertNotNull(Object obj, String msg) {
        if (obj == null) throw new AssertionError("Assertion failed (expected non-null): " + msg);
    }

    public void testFindPersistentClasses() {
        CallGraphAnalyzer callGraph = new CallGraphAnalyzer();
        CriticalPathAnalyzer analyzer = new CriticalPathAnalyzer(callGraph);

        List<CodeType> types = new ArrayList<>();
        CodeType t1 = new CodeType();
        t1.setFqn("com.tcs.bancs.AM.Account");
        t1.setSimpleName("Account");
        t1.setPackageFqn("com.tcs.bancs.AM");
        t1.setKind("CLASS");
        types.add(t1);

        CodeType t2 = new CodeType();
        t2.setFqn("com.tcs.bancs.AM.AccountService");
        t2.setSimpleName("AccountService");
        t2.setPackageFqn("com.tcs.bancs.AM");
        t2.setKind("CLASS");
        types.add(t2);

        List<CodeMethod> methods = new ArrayList<>();
        // Account has Get, Create, Modify
        methods.add(createMethod("com.tcs.bancs.AM.Account.Get(String)", "Get", "com.tcs.bancs.AM.Account", 2));
        methods.add(createMethod("com.tcs.bancs.AM.Account.Create()", "Create", "com.tcs.bancs.AM.Account", 3));
        methods.add(createMethod("com.tcs.bancs.AM.Account.Modify(String)", "Modify", "com.tcs.bancs.AM.Account", 4));

        // AccountService only has process
        methods.add(createMethod("com.tcs.bancs.AM.AccountService.process()", "process", "com.tcs.bancs.AM.AccountService", 5));

        // Add a Message Object that also happens to have get/create/modify
        CodeType t3 = new CodeType();
        t3.setFqn("com.tcs.bancs.AM.MO_INP_AccountRequest");
        t3.setSimpleName("MO_INP_AccountRequest");
        t3.setPackageFqn("com.tcs.bancs.AM");
        t3.setKind("CLASS");
        types.add(t3);
        methods.add(createMethod("com.tcs.bancs.AM.MO_INP_AccountRequest.Get()", "Get", "com.tcs.bancs.AM.MO_INP_AccountRequest", 1));
        methods.add(createMethod("com.tcs.bancs.AM.MO_INP_AccountRequest.Create()", "Create", "com.tcs.bancs.AM.MO_INP_AccountRequest", 1));
        methods.add(createMethod("com.tcs.bancs.AM.MO_INP_AccountRequest.Modify()", "Modify", "com.tcs.bancs.AM.MO_INP_AccountRequest", 1));

        List<CriticalPathAnalyzer.PersistentClassSummary> persistentClasses = analyzer.findPersistentClasses(types, methods);
        assertNotNull(persistentClasses, "Persistent classes list should not be null");
        assertEquals(1, persistentClasses.size(), "Should identify exactly 1 persistent class, excluding MO_*");
        assertEquals("com.tcs.bancs.AM.Account", persistentClasses.get(0).fqn, "Detected class should be Account");
        assertTrue(persistentClasses.get(0).persistentMethods.contains("Get"), "Should have Get");
        assertTrue(persistentClasses.get(0).persistentMethods.contains("Create"), "Should have Create");
        assertTrue(persistentClasses.get(0).persistentMethods.contains("Modify"), "Should have Modify");
    }

    public void testTaskArchetypesClassification() {
        CallGraphAnalyzer callGraph = new CallGraphAnalyzer();

        String entryPoint = "com.tcs.bancs.AM.AMTOExecuteWorkflow.execute()";
        String commonTask = "com.tcs.bancs.common.CMTCValidateLimits.execute()";
        String targetMethod = "com.tcs.bancs.AM.Account.Modify(String)";

        List<String> methodFqns = List.of(entryPoint, commonTask, targetMethod);

        List<CodeRelationship> rels = new ArrayList<>();
        rels.add(createRel(entryPoint, commonTask));
        rels.add(createRel(commonTask, targetMethod));

        callGraph.rebuild(methodFqns, rels);

        CriticalPathAnalyzer analyzer = new CriticalPathAnalyzer(callGraph);

        Map<String, CodeMethod> methodMap = new HashMap<>();
        methodMap.put(entryPoint, createMethod(entryPoint, "execute", "com.tcs.bancs.AM.AMTOExecuteWorkflow", 5));
        methodMap.put(commonTask, createMethod(commonTask, "execute", "com.tcs.bancs.common.CMTCValidateLimits", 4));
        methodMap.put(targetMethod, createMethod(targetMethod, "Modify", "com.tcs.bancs.AM.Account", 3));

        Map<String, CodeType> typeMap = new HashMap<>();
        CodeType accType = new CodeType();
        accType.setFqn("com.tcs.bancs.AM.Account");
        accType.setSimpleName("Account");
        accType.setPackageFqn("com.tcs.bancs.AM");
        accType.setKind("CLASS");
        typeMap.put(accType.getFqn(), accType);

        CriticalPathAnalyzer.CriticalPathReport report = analyzer.analyzeCriticalPaths(
            "com.tcs.bancs.AM.Account",
            callGraph.getCallGraph(),
            methodMap,
            typeMap,
            true
        );

        assertNotNull(report, "Report should not be null");
        assertNotNull(report.primaryPath, "Primary path should not be null");
        assertEquals(3, report.primaryPath.nodes.size(), "Should have 3 nodes in path");
        assertEquals("TASK-OWN", report.primaryPath.nodes.get(0).archetypeBadge, "First node should be TASK-OWN");
        assertEquals("TASK-COMMON", report.primaryPath.nodes.get(1).archetypeBadge, "Second node should be TASK-COMMON");
        assertEquals("PERSISTENT", report.primaryPath.nodes.get(2).archetypeBadge, "Third node should be PERSISTENT");
    }

    public void testAnalyzeCriticalPaths() {
        CallGraphAnalyzer callGraph = new CallGraphAnalyzer();

        String entryPoint = "com.tcs.bancs.AM.AMBTTransferFunds.execute()";
        String service = "com.tcs.bancs.AM.AccountService.transferFunds()";
        String targetMethod = "com.tcs.bancs.AM.Account.Modify(String)";
        String auditSink = "com.tcs.bancs.audit.AuditTrailService.logAuditEvent()";

        List<String> methodFqns = List.of(entryPoint, service, targetMethod, auditSink);

        List<CodeRelationship> rels = new ArrayList<>();
        rels.add(createRel(entryPoint, service));
        rels.add(createRel(service, targetMethod));
        rels.add(createRel(targetMethod, auditSink));

        callGraph.rebuild(methodFqns, rels);

        CriticalPathAnalyzer analyzer = new CriticalPathAnalyzer(callGraph);

        Map<String, CodeMethod> methodMap = new HashMap<>();
        methodMap.put(entryPoint, createMethod(entryPoint, "execute", "com.tcs.bancs.AM.AMBTTransferFunds", 8));
        methodMap.put(service, createMethod(service, "transferFunds", "com.tcs.bancs.AM.AccountService", 6));
        methodMap.put(targetMethod, createMethod(targetMethod, "Modify", "com.tcs.bancs.AM.Account", 4));
        methodMap.put(auditSink, createMethod(auditSink, "logAuditEvent", "com.tcs.bancs.audit.AuditTrailService", 2));

        Map<String, CodeType> typeMap = new HashMap<>();
        CodeType accType = new CodeType();
        accType.setFqn("com.tcs.bancs.AM.Account");
        accType.setSimpleName("Account");
        accType.setPackageFqn("com.tcs.bancs.AM");
        accType.setKind("CLASS");
        typeMap.put(accType.getFqn(), accType);

        CriticalPathAnalyzer.CriticalPathReport report = analyzer.analyzeCriticalPaths(
            "com.tcs.bancs.AM.Account",
            callGraph.getCallGraph(),
            methodMap,
            typeMap,
            true
        );

        assertNotNull(report, "Report should not be null");
        assertEquals("com.tcs.bancs.AM.Account", report.targetClassFqn, "Target class FQN");
        assertTrue(report.isPersistent, "Should be persistent");
        assertNotNull(report.primaryPath, "Primary path should not be null");

        CriticalPathAnalyzer.CriticalPath path = report.primaryPath;
        assertEquals(4, path.nodes.size(), "Should have 4 nodes along the critical path");
        assertEquals(3, path.edges.size(), "Should have 3 edges along the critical path");

        assertEquals("ENTRY_POINT", path.nodes.get(0).role, "First node role");
        assertEquals("MUTATE", path.nodes.get(0).archetypeBadge, "First node archetype");

        assertEquals("INTERMEDIARY", path.nodes.get(1).role, "Second node role");

        assertEquals("PERSISTENT_TARGET", path.nodes.get(2).role, "Third node role");
        assertEquals("PERSISTENT", path.nodes.get(2).archetypeBadge, "Third node archetype");

        assertEquals("DOWNSTREAM_SINK", path.nodes.get(3).role, "Fourth node role");
        assertEquals("AUDIT", path.nodes.get(3).archetypeBadge, "Fourth node archetype");

        for (CriticalPathAnalyzer.CriticalPathNode node : path.nodes) {
            assertNotNull(node.x, "Node X should not be null");
            assertNotNull(node.y, "Node Y should not be null");
            assertFalse(Double.isNaN(node.x), "Node X should not be NaN");
            assertFalse(Double.isNaN(node.y), "Node Y should not be NaN");
        }

        assertEquals(3, path.metrics.length, "Path length (hops)");
        assertEquals(20, path.metrics.cumulativeComplexity, "Cumulative complexity: 8 + 6 + 4 + 2 = 20");
    }

    public void testPersistentClassWithSModifyAndMModify() {
        CallGraphAnalyzer callGraph = new CallGraphAnalyzer();
        CriticalPathAnalyzer analyzer = new CriticalPathAnalyzer(callGraph);

        List<CodeType> types = new ArrayList<>();
        CodeType t1 = new CodeType();
        t1.setFqn("com.tcs.bancs.DP.DepositContract");
        t1.setSimpleName("DepositContract");
        t1.setPackageFqn("com.tcs.bancs.DP");
        t1.setKind("CLASS");
        types.add(t1);

        List<CodeMethod> methods = new ArrayList<>();
        methods.add(createMethod("com.tcs.bancs.DP.DepositContract.Get(String)", "Get", "com.tcs.bancs.DP.DepositContract", 2));
        methods.add(createMethod("com.tcs.bancs.DP.DepositContract.Create()", "Create", "com.tcs.bancs.DP.DepositContract", 3));
        methods.add(createMethod("com.tcs.bancs.DP.DepositContract.SModify(String)", "SModify", "com.tcs.bancs.DP.DepositContract", 4));
        methods.add(createMethod("com.tcs.bancs.DP.DepositContract.MModify(String)", "MModify", "com.tcs.bancs.DP.DepositContract", 4));

        List<CriticalPathAnalyzer.PersistentClassSummary> persistentClasses = analyzer.findPersistentClasses(types, methods);
        assertNotNull(persistentClasses, "Persistent classes list should not be null");
        assertEquals(1, persistentClasses.size(), "Should identify DepositContract as persistent class with SModify/MModify");
        assertEquals("com.tcs.bancs.DP.DepositContract", persistentClasses.get(0).fqn, "Detected class should be DepositContract");
        assertTrue(persistentClasses.get(0).persistentMethods.contains("Get"), "Should have Get");
        assertTrue(persistentClasses.get(0).persistentMethods.contains("Create"), "Should have Create");
        assertTrue(persistentClasses.get(0).persistentMethods.contains("SModify"), "Should have SModify");
        assertTrue(persistentClasses.get(0).persistentMethods.contains("MModify"), "Should have MModify");
    }

    public void testSameClassThisGetResolution() throws Exception {
        CallGraphAnalyzer callGraph = new CallGraphAnalyzer();

        String callerMethod = "com.tcs.bancs.AM.Account.refresh()";
        String targetMethod = "com.tcs.bancs.AM.Account.Get(String)";

        List<String> methodFqns = List.of(callerMethod, targetMethod);

        // When this.Get(...) or Get(...) is invoked, AstVisitor creates target as "com.tcs.bancs.AM.Account.Get" (without param types)
        List<CodeRelationship> rels = new ArrayList<>();
        rels.add(createRel(callerMethod, "com.tcs.bancs.AM.Account.Get"));

        callGraph.rebuild(methodFqns, rels);

        org.jgrapht.Graph<String, org.jgrapht.graph.DefaultEdge> g = callGraph.getCallGraph();
        assertNotNull(g, "Graph should not be null");
        assertTrue(g.containsEdge(callerMethod, targetMethod), "Edge should be resolved from refresh() to Get(String)");
    }

    private static CodeMethod createMethod(String fqn, String simple, String typeFqn, int complexity) {
        CodeMethod m = new CodeMethod();
        m.setId(fqn);
        m.setFqn(fqn);
        m.setSimpleName(simple);
        m.setDeclaringTypeFqn(typeFqn);
        m.setCyclomaticComplexity(complexity);
        return m;
    }

    private static CodeRelationship createRel(String from, String to) {
        CodeRelationship r = new CodeRelationship();
        r.setFromEntityFqn(from);
        r.setToEntityFqn(to);
        r.setKind("CALLS");
        return r;
    }
}
