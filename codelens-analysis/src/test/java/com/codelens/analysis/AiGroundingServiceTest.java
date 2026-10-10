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
        AiGroundingServiceTest test = new AiGroundingServiceTest();
        test.testAskQuestionAndDeterministicSynthesis();
        test.testConnectionHandshake();
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

    public void testConnectionHandshake() {
        AiGroundingService ai = new AiGroundingService();

        // 1. Local provider handshake
        AiGroundingService.ConnectionTestResult localRes = ai.testConnection("local", null, null, null);
        assertNotNull(localRes, "Local test result must not be null");
        assertTrue(localRes.success, "Local provider test should succeed");
        assertEquals("local", localRes.provider, "Provider should be local");
        assertTrue(localRes.message.contains("Local Fact Synthesizer"), "Message should mention local synthesizer");
        assertTrue(localRes.latencyMs >= 0, "Latency should be non-negative");

        // 2. Ollama on unreachable port
        AiGroundingService.ConnectionTestResult ollamaFail = ai.testConnection("ollama", "llama3", "http://localhost:19999", null);
        assertNotNull(ollamaFail, "Ollama fail result must not be null");
        assertTrue(!ollamaFail.success, "Ollama on unreachable port should fail");
        assertEquals("ollama", ollamaFail.provider, "Provider should be ollama");
        assertTrue(ollamaFail.message.toLowerCase().contains("failed") || ollamaFail.message.toLowerCase().contains("refused"),
            "Message should indicate connection failure: " + ollamaFail.message);

        // 3. OpenAI missing API key
        AiGroundingService.ConnectionTestResult openAiNoKey = ai.testConnection("openai", "gpt-4o-mini", null, "");
        assertNotNull(openAiNoKey, "OpenAI no key result must not be null");
        assertTrue(!openAiNoKey.success, "OpenAI without API key should fail");
        assertEquals("openai", openAiNoKey.provider, "Provider should be openai");
        assertTrue(openAiNoKey.message.contains("API key is required"),
            "Message should indicate API key required: " + openAiNoKey.message);

        // 4. OpenAI on unreachable custom endpoint
        AiGroundingService.ConnectionTestResult openAiFail = ai.testConnection("openai", "gpt-4o-mini", "http://localhost:19999", "sk-mock-key");
        assertNotNull(openAiFail, "OpenAI unreachable result must not be null");
        assertTrue(!openAiFail.success, "OpenAI unreachable should fail");
        assertEquals("openai", openAiFail.provider, "Provider should be openai");

        // 5. Unknown provider
        AiGroundingService.ConnectionTestResult unknown = ai.testConnection("unsupported-engine", null, null, null);
        assertNotNull(unknown, "Unknown provider result must not be null");
        assertTrue(!unknown.success, "Unknown provider should fail");
        assertTrue(unknown.message.contains("Unrecognized"), "Message should mention Unrecognized provider");

        // 6. Dynamic Model Discovery
        List<String> localModels = ai.discoverModels("local", null, null);
        assertNotNull(localModels, "Local models must not be null");
        assertTrue(localModels.contains("local-facts"), "Local models should contain local-facts");

        List<String> ollamaFallback = ai.discoverModels("ollama", "http://localhost:19999", null);
        assertNotNull(ollamaFallback, "Ollama fallback models must not be null");
        assertTrue(ollamaFallback.contains("llama3"), "Fallback should include llama3");

        List<String> openAiDefault = ai.discoverModels("openai", null, "");
        assertNotNull(openAiDefault, "OpenAI default models must not be null");
        assertTrue(openAiDefault.contains("gpt-4o-mini"), "OpenAI default should include gpt-4o-mini");
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
