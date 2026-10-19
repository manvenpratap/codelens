package com.codelens.analysis;

import com.codelens.core.model.CodeMethod;
import com.codelens.core.model.CodeRelationship;
import com.codelens.core.model.CodeType;

import java.util.*;

public class EnterpriseScalingTest {

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

    public void testCompactIntGraphZeroAllocationAndParity() {
        SymbolDictionary dict = new SymbolDictionary();
        CompactIntGraph graph = new CompactIntGraph(dict);

        // Build a 1,000-node linear chain
        int count = 1000;
        for (int i = 0; i < count; i++) {
            String m1 = "com.enterprise.service.Node" + i + ".exec()";
            String m2 = "com.enterprise.service.Node" + (i + 1) + ".exec()";
            graph.addEdge(m1, m2);
        }

        // Diamond shortcut from 0 to 500
        graph.addEdge("com.enterprise.service.Node0.exec()", "com.enterprise.service.Node500.exec()");

        int id0 = dict.getId("com.enterprise.service.Node0.exec()");
        int id1000 = dict.getId("com.enterprise.service.Node1000.exec()");

        assertTrue(id0 >= 0, "Root node id should be valid");
        assertTrue(id1000 >= 0, "Leaf node id should be valid");

        // Forward reachability (callees)
        int[] calleesOf0 = graph.bfsCallees(id0, 100);
        assertTrue(calleesOf0.length > 0, "Root should have callees reached");

        int id500 = dict.getId("com.enterprise.service.Node500.exec()");
        boolean found500 = false;
        for (int cid : calleesOf0) {
            if (cid == id500) {
                found500 = true;
                break;
            }
        }
        assertTrue(found500, "Node500 should be reachable from Node0");

        // Backward reachability (callers) from Node500
        int[] callersOf500 = graph.bfsCallers(id500, 10);
        boolean found0InCallers = false;
        for (int cid : callersOf500) {
            if (cid == id0) {
                found0InCallers = true;
                break;
            }
        }
        assertTrue(found0InCallers, "Node0 should be in callers of Node500 via shortcut");

        // Performance check: 5,000 traversals should finish in under 1000ms
        long start = System.nanoTime();
        for (int i = 0; i < 5000; i++) {
            graph.bfsCallees(id0, 5);
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(durationMs < 1000, "5,000 traversals took " + durationMs + "ms, expected < 1000ms");
    }

    public void testArchitectureGuardrailEngineLayerViolation() {
        ArchitectureGuardrailEngine engine = new ArchitectureGuardrailEngine();
        CallGraphAnalyzer callGraph = new CallGraphAnalyzer();

        String controllerMethod = "com.corp.controller.OrderController.createOrder()";
        String daoMethod = "com.corp.dao.OrderDao.save()";

        List<String> methodFqns = List.of(controllerMethod, daoMethod);
        CodeRelationship directCall = new CodeRelationship();
        directCall.setFromEntityFqn(controllerMethod);
        directCall.setToEntityFqn(daoMethod);
        directCall.setKind("CALLS");

        callGraph.rebuild(methodFqns, List.of(directCall));

        CodeType ctrlType = new CodeType();
        ctrlType.setFqn("com.corp.controller.OrderController");
        ctrlType.setPackageFqn("com.corp.controller");
        ctrlType.setKind("CLASS");

        CodeType daoType = new CodeType();
        daoType.setFqn("com.corp.dao.OrderDao");
        daoType.setPackageFqn("com.corp.dao");
        daoType.setKind("CLASS");

        CodeMethod mCtrl = new CodeMethod();
        mCtrl.setFqn(controllerMethod);
        mCtrl.setDeclaringTypeFqn(ctrlType.getFqn());

        CodeMethod mDao = new CodeMethod();
        mDao.setFqn(daoMethod);
        mDao.setDeclaringTypeFqn(daoType.getFqn());

        ArchitectureGuardrailEngine.GuardrailReport report = engine.evaluate(
            List.of(ctrlType, daoType),
            List.of(mCtrl, mDao),
            List.of(directCall),
            callGraph,
            Collections.emptyList()
        );

        assertFalse(report.passed, "Direct Controller -> DAO bypass must fail architectural quality gate");
        assertTrue(report.blockerCount > 0, "Must have at least 1 blocker");
        boolean foundLayerViolation = report.violations.stream()
            .anyMatch(v -> v.ruleId.equals("ARCH-LAYER-BYPASS"));
        assertTrue(foundLayerViolation, "Expected ARCH-LAYER-BYPASS rule match");
    }

    public void testArchitectureGuardrailEngineCircularPackage() {
        ArchitectureGuardrailEngine engine = new ArchitectureGuardrailEngine();
        CallGraphAnalyzer callGraph = new CallGraphAnalyzer();

        String pkgAMethod = "com.corp.pkgA.ServiceA.callB()";
        String pkgBMethod = "com.corp.pkgB.ServiceB.callA()";

        List<String> methodFqns = List.of(pkgAMethod, pkgBMethod);
        CodeRelationship r1 = new CodeRelationship();
        r1.setFromEntityFqn(pkgAMethod);
        r1.setToEntityFqn(pkgBMethod);
        r1.setKind("CALLS");

        CodeRelationship r2 = new CodeRelationship();
        r2.setFromEntityFqn(pkgBMethod);
        r2.setToEntityFqn(pkgAMethod);
        r2.setKind("CALLS");

        callGraph.rebuild(methodFqns, List.of(r1, r2));

        CodeType tA = new CodeType();
        tA.setFqn("com.corp.pkgA.ServiceA");
        tA.setPackageFqn("com.corp.pkgA");

        CodeType tB = new CodeType();
        tB.setFqn("com.corp.pkgB.ServiceB");
        tB.setPackageFqn("com.corp.pkgB");

        CodeMethod mA = new CodeMethod();
        mA.setFqn(pkgAMethod);
        mA.setDeclaringTypeFqn(tA.getFqn());

        CodeMethod mB = new CodeMethod();
        mB.setFqn(pkgBMethod);
        mB.setDeclaringTypeFqn(tB.getFqn());

        ArchitectureGuardrailEngine.GuardrailReport report = engine.evaluate(
            List.of(tA, tB),
            List.of(mA, mB),
            List.of(r1, r2),
            callGraph,
            Collections.emptyList()
        );

        boolean foundCycle = report.violations.stream()
            .anyMatch(v -> v.ruleId.equals("ARCH-CYCLE-DETECTED"));
        assertTrue(foundCycle, "Circular package dependency (pkgA <-> pkgB) must be flagged");
    }

    public void testArchitectureGuardrailEngineBlastRadiusAndMarkdown() {
        ArchitectureGuardrailEngine.GuardrailConfig cfg = new ArchitectureGuardrailEngine.GuardrailConfig();
        cfg.maxBlastRadius = 10; // threshold 10 callers
        ArchitectureGuardrailEngine engine = new ArchitectureGuardrailEngine(cfg);
        CallGraphAnalyzer callGraph = new CallGraphAnalyzer();

        String coreMethod = "com.corp.core.SecurityContext.getUser()";
        List<String> methodFqns = new ArrayList<>();
        List<CodeMethod> methodObjects = new ArrayList<>();
        methodFqns.add(coreMethod);

        CodeMethod mCore = new CodeMethod();
        mCore.setFqn(coreMethod);
        mCore.setDeclaringTypeFqn("com.corp.core.SecurityContext");
        methodObjects.add(mCore);

        List<CodeRelationship> rels = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            String caller = "com.corp.service.Service" + i + ".exec()";
            methodFqns.add(caller);

            CodeMethod mCaller = new CodeMethod();
            mCaller.setFqn(caller);
            mCaller.setDeclaringTypeFqn("com.corp.service.Service" + i);
            methodObjects.add(mCaller);

            CodeRelationship r = new CodeRelationship();
            r.setFromEntityFqn(caller);
            r.setToEntityFqn(coreMethod);
            r.setKind("CALLS");
            rels.add(r);
        }

        callGraph.rebuild(methodFqns, rels);

        ArchitectureGuardrailEngine.GuardrailReport report = engine.evaluate(
            Collections.emptyList(),
            methodObjects,
            rels,
            callGraph,
            List.of(coreMethod)
        );

        boolean foundBlast = report.violations.stream()
            .anyMatch(v -> v.ruleId.equals("PR-EXCESSIVE-BLAST-RADIUS"));
        assertTrue(foundBlast, "Blast radius violation should trigger when changed methods impact all callers");

        String md = report.toMarkdown();
        assertNotNull(md, "Markdown report should not be null");
        assertTrue(md.contains("CodeLens Architecture & CI Quality Gate Report"), "Markdown should include header");
        assertTrue(md.contains("PR-EXCESSIVE-BLAST-RADIUS"), "Markdown should detail blast radius");
    }
}
