package com.codelens.analysis;

import com.codelens.core.model.CodeMethod;
import com.codelens.core.model.CodeRelationship;
import com.codelens.core.model.CodeType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UnconstrainedScopeTest {

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + " (expected: " + expected + ", got: " + actual + ")");
    }

    public void testBfsDoesNotTruncateAt2500Nodes() {
        CallGraphAnalyzer analyzer = new CallGraphAnalyzer();
        String root = "com.product.core.RootService.execute()";
        List<String> methodFqns = new ArrayList<>();
        methodFqns.add(root);

        List<CodeRelationship> rels = new ArrayList<>();
        int nodeCount = 2500;
        for (int i = 0; i < nodeCount; i++) {
            String callee = "com.product.core.Worker" + i + ".doWork()";
            methodFqns.add(callee);

            CodeRelationship r = new CodeRelationship();
            r.setFromEntityFqn(root);
            r.setToEntityFqn(callee);
            r.setKind("CALLS");
            rels.add(r);
        }

        analyzer.rebuild(methodFqns, rels);

        List<CallGraphAnalyzer.GraphNode> callees = analyzer.callees(root, 1);
        assertEquals(nodeCount, callees.size(), "BFS must not truncate at 2500 nodes");
    }

    public void testTouchPointsNotTruncatedAbove500() {
        ModuleDependencyAnalyzer analyzer = new ModuleDependencyAnalyzer();
        List<com.codelens.core.model.CodePackage> packages = new ArrayList<>();
        List<com.codelens.core.model.CodeType> types = new ArrayList<>();
        List<com.codelens.core.model.CodeMethod> methods = new ArrayList<>();
        List<com.codelens.core.model.CodeField> fields = new ArrayList<>();
        List<com.codelens.core.model.CodeRelationship> rels = new ArrayList<>();

        com.codelens.core.model.CodePackage p1 = new com.codelens.core.model.CodePackage("com.alpha");
        p1.setName("alpha");
        packages.add(p1);
        com.codelens.core.model.CodePackage p2 = new com.codelens.core.model.CodePackage("com.beta");
        p2.setName("beta");
        packages.add(p2);

        com.codelens.core.model.CodeType t1 = new com.codelens.core.model.CodeType();
        t1.setFqn("com.alpha.AlphaService");
        t1.setSimpleName("AlphaService");
        t1.setPackageFqn("com.alpha");
        types.add(t1);

        com.codelens.core.model.CodeType t2 = new com.codelens.core.model.CodeType();
        t2.setFqn("com.beta.BetaService");
        t2.setSimpleName("BetaService");
        t2.setPackageFqn("com.beta");
        types.add(t2);

        int count = 650;
        for (int i = 0; i < count; i++) {
            CodeRelationship r = new CodeRelationship();
            r.setFromEntityFqn("com.alpha.AlphaService.send" + i + "()");
            r.setToEntityFqn("com.beta.BetaService.recv" + i + "()");
            r.setKind("CALLS");
            r.setSourceLine(i + 1);
            rels.add(r);
        }

        ModuleDependencyAnalyzer.ModuleDependencyInsights insights =
            analyzer.analyzeModule("alpha", packages, types, methods, fields, rels);
        assertEquals(count, insights.totalTouchPoints, "Total touchpoints must match full 650");
        assertEquals(1, insights.outgoingModules.size(), "Should have 1 outgoing module");
        assertEquals(count, insights.outgoingModules.get(0).touchPoints.size(), "Sample list must not cap at 500");
    }

    public void testCriticalPathDoesNotTruncateUpstreamPaths() {
        CallGraphAnalyzer callGraph = new CallGraphAnalyzer();
        List<String> methodFqns = new ArrayList<>();
        List<CodeRelationship> rels = new ArrayList<>();

        // Persistent entity Account with Get, Create, Modify
        String p1 = "com.product.Account.Get()";
        String p2 = "com.product.Account.Create()";
        String p3 = "com.product.Account.Modify()";
        methodFqns.add(p1);
        methodFqns.add(p2);
        methodFqns.add(p3);

        Map<String, CodeType> typeMap = new HashMap<>();
        CodeType type = new CodeType();
        type.setFqn("com.product.Account");
        type.setSimpleName("Account");
        type.setPackageFqn("com.product");
        type.setKind("CLASS");
        typeMap.put("com.product.Account", type);

        Map<String, CodeMethod> methodMap = new HashMap<>();
        methodMap.put(p1, createMethod(p1, "Get", "com.product.Account"));
        methodMap.put(p2, createMethod(p2, "Create", "com.product.Account"));
        methodMap.put(p3, createMethod(p3, "Modify", "com.product.Account"));

        // 75 different entry controllers calling Account.Create
        int controllerCount = 75;
        for (int i = 0; i < controllerCount; i++) {
            String ctrl = "com.product.Controller" + i + "Endpoint.handle()";
            methodFqns.add(ctrl);
            methodMap.put(ctrl, createMethod(ctrl, "handle", "com.product.Controller" + i + "Endpoint"));

            CodeRelationship r = new CodeRelationship();
            r.setFromEntityFqn(ctrl);
            r.setToEntityFqn(p2);
            r.setKind("CALLS");
            rels.add(r);
        }

        callGraph.rebuild(methodFqns, rels);
        CriticalPathAnalyzer criticalAnalyzer = new CriticalPathAnalyzer(callGraph);

        CriticalPathAnalyzer.CriticalPathReport report =
            criticalAnalyzer.analyzeCriticalPaths(
                "com.product.Account",
                callGraph.getCallGraph(),
                methodMap,
                typeMap,
                true
            );

        assertTrue(report.isPersistent, "Account must be recognized as persistent entity");
        // Discovered upstream paths should not be cut off at 60
        assertTrue(report.totalDiscoveredPaths >= 75,
            "Critical path candidate discovery must not be capped at 60 (got: " + report.totalDiscoveredPaths + ")");
    }

    private static CodeMethod createMethod(String fqn, String simple, String declType) {
        CodeMethod m = new CodeMethod();
        m.setFqn(fqn);
        m.setSimpleName(simple);
        m.setDeclaringTypeFqn(declType);
        m.setStartLine(10);
        m.setEndLine(25);
        m.setCyclomaticComplexity(2);
        return m;
    }
}
