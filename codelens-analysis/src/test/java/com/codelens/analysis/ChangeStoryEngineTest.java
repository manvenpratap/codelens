package com.codelens.analysis;

import com.codelens.core.model.CodeMethod;
import com.codelens.core.model.CodeRelationship;
import com.codelens.core.model.CodeType;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import java.util.*;

public class ChangeStoryEngineTest {

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected: " + expected + ", got: " + actual + ")");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertNotNull(Object obj, String message) {
        if (obj == null) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        new ChangeStoryEngineTest().testSynthesizeChangeStory();
        System.out.println("ChangeStoryEngineTest passed!");
    }

    public void testSynthesizeChangeStory() {
        List<CodeType> types = new ArrayList<>();
        types.add(createType("com.trading.OrderApiController", "OrderApiController", "/src/com/trading/OrderApiController.java", 10));
        types.add(createType("com.trading.OrderService", "OrderService", "/src/com/trading/OrderService.java", 20));
        types.add(createType("com.trading.OrderDao", "OrderDao", "/src/com/trading/OrderDao.java", 50));
        types.add(createType("com.trading.OrderApiControllerTest", "OrderApiControllerTest", "/test/com/trading/OrderApiControllerTest.java", 1));

        List<CodeMethod> methods = new ArrayList<>();
        methods.add(createMethod("com.trading.OrderApiController.createOrder()", "createOrder", "com.trading.OrderApiController", 15, 25));
        methods.add(createMethod("com.trading.OrderService.processOrder()", "processOrder", "com.trading.OrderService", 25, 45));
        methods.add(createMethod("com.trading.OrderDao.saveOrder()", "saveOrder", "com.trading.OrderDao", 55, 70));
        methods.add(createMethod("com.trading.OrderApiControllerTest.testCreateOrder()", "testCreateOrder", "com.trading.OrderApiControllerTest", 10, 20));

        Graph<String, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);
        for (CodeMethod m : methods) graph.addVertex(m.getFqn());
        graph.addEdge("com.trading.OrderApiController.createOrder()", "com.trading.OrderService.processOrder()");
        graph.addEdge("com.trading.OrderService.processOrder()", "com.trading.OrderDao.saveOrder()");
        graph.addEdge("com.trading.OrderApiControllerTest.testCreateOrder()", "com.trading.OrderService.processOrder()");

        List<CodeRelationship> relationships = new ArrayList<>();
        relationships.add(new CodeRelationship("r1", "com.trading.OrderApiController.createOrder()", "endpoint:POST /api/v1/orders", "EXPOSES_ENDPOINT", 16));
        relationships.add(new CodeRelationship("r2", "com.trading.OrderService.processOrder()", "table:TRD_ORDERS", "ACCESSES_TABLE", 30));

        StoryEngine storyEngine = new StoryEngine();
        ChangeStoryEngine engine = new ChangeStoryEngine();

        // Diff touches line 30 in OrderService.java
        Map<String, List<int[]>> fileLineRanges = new HashMap<>();
        fileLineRanges.put("OrderService.java", List.of(new int[]{28, 35}));

        ChangeStoryEngine.ChangeStory story = engine.synthesizeStory(
            fileLineRanges, types, methods, relationships, graph, storyEngine, "main", "feature/orders"
        );

        assertNotNull(story, "Story should not be null");
        assertEquals("main", story.baseRef, "Base ref");
        assertEquals("feature/orders", story.headRef, "Head ref");
        assertEquals(1, story.changedMethodsCount, "Changed methods count");
        assertEquals(1, story.changedClassesCount, "Changed classes count");
        assertEquals("processOrder", story.changedMethods.get(0).simpleName, "Changed method name");

        // Touch points: OrderApiController and OrderApiControllerTest call processOrder
        assertTrue(story.totalTouchPoints >= 2, "Should have at least 2 touchpoints");

        // Semantic entities
        assertTrue(story.affectedTables.contains("TRD_ORDERS"), "Should detect affected table TRD_ORDERS");

        // Recommended tests
        assertTrue(story.recommendedTests.contains("OrderApiControllerTest"), "Should recommend OrderApiControllerTest");

        // Storylines affected
        assertTrue(!story.affectedStorylines.isEmpty(), "Should identify affected storyline");

        // Risk level
        assertNotNull(story.riskLevel, "Risk level should be set");

        // Narratives
        assertNotNull(story.narrativeBeforeChange, "Before narrative");
        assertNotNull(story.narrativeChangeSummary, "Change summary");
        assertNotNull(story.narrativeImpact, "Impact narrative");
        assertTrue(story.reviewChecklist.size() >= 2, "Checklist should have items");
    }

    private CodeType createType(String fqn, String simple, String file, int line) {
        CodeType t = new CodeType();
        t.setFqn(fqn);
        t.setSimpleName(simple);
        t.setKind("CLASS");
        t.setSourceFile(file);
        t.setStartLine(line);
        return t;
    }

    private CodeMethod createMethod(String fqn, String simple, String decl, int start, int end) {
        CodeMethod m = new CodeMethod();
        m.setId(fqn);
        m.setFqn(fqn);
        m.setSimpleName(simple);
        m.setDeclaringTypeFqn(decl);
        m.setStartLine(start);
        m.setEndLine(end);
        m.setCyclomaticComplexity(2);
        return m;
    }
}
