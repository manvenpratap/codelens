package com.codelens.analysis;

import com.codelens.core.model.CodeMethod;
import com.codelens.core.model.CodeRelationship;
import com.codelens.core.model.CodeType;

import java.util.*;

public class StoryEngineTest {

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

    public static void main(String[] args) {
        new StoryEngineTest().testDiscoverStorylinesAndNarrativeSynthesis();
        System.out.println("StoryEngineTest: ALL CHECKS PASSED!");
    }

    public void testDiscoverStorylinesAndNarrativeSynthesis() {
        CallGraphAnalyzer callGraph = new CallGraphAnalyzer();

        // Build a classic enterprise banking flow:
        // LoanController.disburse() -> LoanValidator.checkLimits() -> LoanService.processDisbursement() -> LoanDao.save() -> AuditPublisher.record()
        List<CodeType> types = new ArrayList<>();
        types.add(createType("com.bank.loan.LoanController", "LoanController", "src/LoanController.java", 10));
        types.add(createType("com.bank.loan.LoanValidator", "LoanValidator", "src/LoanValidator.java", 20));
        types.add(createType("com.bank.loan.LoanService", "LoanService", "src/LoanService.java", 30));
        types.add(createType("com.bank.loan.LoanDao", "LoanDao", "src/LoanDao.java", 40));
        types.add(createType("com.bank.loan.AuditPublisher", "AuditPublisher", "src/AuditPublisher.java", 50));

        List<CodeMethod> methods = new ArrayList<>();
        methods.add(createMethod("com.bank.loan.LoanController.disburse()", "disburse", "com.bank.loan.LoanController", 12));
        methods.add(createMethod("com.bank.loan.LoanValidator.checkLimits()", "checkLimits", "com.bank.loan.LoanValidator", 22));
        methods.add(createMethod("com.bank.loan.LoanService.processDisbursement()", "processDisbursement", "com.bank.loan.LoanService", 35));
        methods.add(createMethod("com.bank.loan.LoanDao.save()", "save", "com.bank.loan.LoanDao", 45));
        methods.add(createMethod("com.bank.loan.AuditPublisher.record()", "record", "com.bank.loan.AuditPublisher", 55));

        List<String> methodFqns = new ArrayList<>();
        for (CodeMethod m : methods) {
            methodFqns.add(m.getFqn());
        }

        List<CodeRelationship> rels = new ArrayList<>();
        rels.add(createRel("com.bank.loan.LoanController.disburse()", "com.bank.loan.LoanValidator.checkLimits()"));
        rels.add(createRel("com.bank.loan.LoanValidator.checkLimits()", "com.bank.loan.LoanService.processDisbursement()"));
        rels.add(createRel("com.bank.loan.LoanService.processDisbursement()", "com.bank.loan.LoanDao.save()"));
        rels.add(createRel("com.bank.loan.LoanDao.save()", "com.bank.loan.AuditPublisher.record()"));

        callGraph.rebuild(methodFqns, rels);

        StoryEngine engine = new StoryEngine();
        List<StoryEngine.StorylineSummary> summaries = engine.discoverStorylines(types, methods, callGraph.getCallGraph());

        assertNotNull(summaries, "Summaries should not be null");
        assertFalse(summaries.isEmpty(), "Summaries should not be empty");

        StoryEngine.StorylineSummary first = summaries.get(0);
        assertEquals("Loan: Disburse Flow", first.title, "Title check");
        assertEquals("TRANSACTION", first.category, "Category check");
        assertEquals(5, first.stepCount, "Step count check");
        assertTrue(first.executiveSummary.contains("LoanController"), "Executive summary should mention entry class");
        assertTrue(first.executiveSummary.contains("LoanService"), "Executive summary should mention domain service");

        // Test detailed storyline extraction
        StoryEngine.Storyline detail = engine.getStorylineByFqn("com.bank.loan.LoanController.disburse()", types, methods, callGraph.getCallGraph());
        assertNotNull(detail, "Detail storyline should not be null");
        assertEquals(5, detail.steps.size(), "Detail should have 5 steps");

        // Check step 1: Entry
        StoryEngine.StorylineStep s1 = detail.steps.get(0);
        assertEquals("ENTRY", s1.role, "Step 1 role");
        assertEquals("LoanController", s1.classSimpleName, "Step 1 class");
        assertEquals("disburse", s1.simpleName, "Step 1 name");

        // Check step 2: Validation
        StoryEngine.StorylineStep s2 = detail.steps.get(1);
        assertEquals("VALIDATION", s2.role, "Step 2 role");
        assertEquals("LoanValidator", s2.classSimpleName, "Step 2 class");

        // Check step 3: Business logic
        StoryEngine.StorylineStep s3 = detail.steps.get(2);
        assertEquals("BUSINESS_LOGIC", s3.role, "Step 3 role");
        assertEquals("LoanService", s3.classSimpleName, "Step 3 class");

        // Check step 4: Persistence
        StoryEngine.StorylineStep s4 = detail.steps.get(3);
        assertEquals("PERSISTENCE", s4.role, "Step 4 role");
        assertEquals("LoanDao", s4.classSimpleName, "Step 4 class");

        // Check step 5: Audit Event
        StoryEngine.StorylineStep s5 = detail.steps.get(4);
        assertEquals("AUDIT_EVENT", s5.role, "Step 5 role");
        assertEquals("AuditPublisher", s5.classSimpleName, "Step 5 class");

        // Verify evidence grounding
        assertFalse(detail.evidence.isEmpty(), "Evidence should not be empty");
        assertEquals("src/LoanController.java", detail.evidence.get(0).sourceFile, "Evidence file");
        assertEquals(12, detail.evidence.get(0).line, "Evidence line");
    }

    private CodeType createType(String fqn, String simpleName, String sourceFile, int line) {
        CodeType t = new CodeType();
        t.setFqn(fqn);
        t.setSimpleName(simpleName);
        t.setKind("CLASS");
        t.setSourceFile(sourceFile);
        t.setStartLine(line);
        return t;
    }

    private CodeMethod createMethod(String fqn, String simpleName, String declTypeFqn, int line) {
        CodeMethod m = new CodeMethod();
        m.setId(fqn);
        m.setFqn(fqn);
        m.setSimpleName(simpleName);
        m.setDeclaringTypeFqn(declTypeFqn);
        m.setStartLine(line);
        m.setCyclomaticComplexity(2);
        return m;
    }

    private CodeRelationship createRel(String from, String to) {
        CodeRelationship r = new CodeRelationship();
        r.setFromEntityFqn(from);
        r.setToEntityFqn(to);
        r.setKind("CALLS");
        return r;
    }
}
