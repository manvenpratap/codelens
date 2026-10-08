package com.codelens.analysis;

import com.codelens.core.model.CodeMethod;
import com.codelens.core.model.CodeRelationship;
import com.codelens.core.model.CodeType;

import java.util.*;

public class AiGroundingServiceTest {

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError("Assertion failed: " + msg);
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
        new AiGroundingServiceTest().testAskQuestionAndDeterministicSynthesis();
        System.out.println("AiGroundingServiceTest: ALL CHECKS PASSED!");
    }

    public void testAskQuestionAndDeterministicSynthesis() {
        CallGraphAnalyzer callGraph = new CallGraphAnalyzer();

        List<CodeType> types = new ArrayList<>();
        types.add(createType("com.bank.loan.LoanController", "LoanController", "src/LoanController.java", 10));
        types.add(createType("com.bank.loan.LoanValidator", "LoanValidator", "src/LoanValidator.java", 20));
        types.add(createType("com.bank.loan.LoanService", "LoanService", "src/LoanService.java", 30));

        List<CodeMethod> methods = new ArrayList<>();
        methods.add(createMethod("com.bank.loan.LoanController.disburse()", "disburse", "com.bank.loan.LoanController", 12));
        methods.add(createMethod("com.bank.loan.LoanValidator.checkLimits()", "checkLimits", "com.bank.loan.LoanValidator", 22));
        methods.add(createMethod("com.bank.loan.LoanService.processDisbursement()", "processDisbursement", "com.bank.loan.LoanService", 35));

        List<String> methodFqns = new ArrayList<>();
        for (CodeMethod m : methods) methodFqns.add(m.getFqn());

        List<CodeRelationship> rels = new ArrayList<>();
        rels.add(createRel("com.bank.loan.LoanController.disburse()", "com.bank.loan.LoanValidator.checkLimits()"));
        rels.add(createRel("com.bank.loan.LoanValidator.checkLimits()", "com.bank.loan.LoanService.processDisbursement()"));

        callGraph.rebuild(methodFqns, rels);

        StoryEngine storyEngine = new StoryEngine();
        List<StoryEngine.StorylineSummary> storylines = storyEngine.discoverStorylines(types, methods, callGraph.getCallGraph());

        AiGroundingService ai = new AiGroundingService();
        assertEquals("local", ai.getProvider(), "Default provider should be local");

        // Ask question
        AiGroundingService.GroundedAnswer answer = ai.askQuestion(
            "How does loan disbursement work in this system?",
            types, methods, callGraph.getCallGraph(), storylines
        );

        assertNotNull(answer, "GroundedAnswer should not be null");
        assertNotNull(answer.answerText, "answerText should not be null");
        assertTrue(answer.answerText.contains("CodeStory Architecture Synthesis"), "Answer should contain header");
        assertTrue(answer.answerText.contains("LoanController"), "Answer should mention LoanController");
        assertTrue(!answer.citations.isEmpty(), "Citations should not be empty");
        assertTrue(!answer.groundedFacts.isEmpty(), "Grounded facts should not be empty");

        // Test symbol explanation
        AiGroundingService.SymbolExplanation exp = ai.explainSymbol(
            "com.bank.loan.LoanValidator.checkLimits()",
            types, methods, callGraph.getCallGraph(), storylines
        );

        assertNotNull(exp, "SymbolExplanation should not be null");
        assertEquals("checkLimits", exp.simpleName, "Simple name check");
        assertEquals(1, exp.incomingCallersCount, "Callers count check");
        assertEquals(1, exp.outgoingCallsCount, "Callees count check");
        assertTrue(exp.detailedNarrative.contains("Inbound Coupling"), "Narrative should mention Inbound Coupling");
    }

    private CodeType createType(String fqn, String simpleName, String file, int line) {
        CodeType t = new CodeType();
        t.setFqn(fqn);
        t.setSimpleName(simpleName);
        t.setSourceFile(file);
        t.setStartLine(line);
        t.setKind("CLASS");
        return t;
    }

    private CodeMethod createMethod(String fqn, String simpleName, String declTypeFqn, int line) {
        CodeMethod m = new CodeMethod();
        m.setId(fqn);
        m.setFqn(fqn);
        m.setSimpleName(simpleName);
        m.setDeclaringTypeFqn(declTypeFqn);
        m.setStartLine(line);
        m.setEndLine(line + 15);
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
