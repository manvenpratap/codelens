package com.codelens.parser;

import com.codelens.core.model.CodeRelationship;
import com.codelens.core.model.CodeType;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;

import java.util.List;

/**
 * Self-contained unit test verifying Semantic Graph Extraction (APIs, Database Tables, Events)
 * according to CodeStory Master Specification Section 9 & 15.
 */
public class SemanticGraphExtractorTest {

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertFalse(boolean condition, String msg) {
        if (condition) throw new AssertionError("Assertion failed (expected false): " + msg);
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + " (expected: " + expected + ", got: " + actual + ")");
    }

    public static void main(String[] args) {
        new SemanticGraphExtractorTest().testSemanticGraphExtraction();
        System.out.println("SemanticGraphExtractorTest: ALL CHECKS PASSED!");
    }

    public void testSemanticGraphExtraction() {
        String sourceCode = ""
            + "package com.example.trading.api;\n"
            + "\n"
            + "import java.util.*;\n"
            + "\n"
            + "@Table(name = \"TRD_ORDERS\")\n"
            + "public class OrderEntity {\n"
            + "    private String id;\n"
            + "}\n"
            + "\n"
            + "@RequestMapping(\"/api/v1/orders\")\n"
            + "public class OrderController {\n"
            + "\n"
            + "    @GetMapping(\"/{orderId}\")\n"
            + "    public String getOrder(String orderId) {\n"
            + "        return \"order-\" + orderId;\n"
            + "    }\n"
            + "\n"
            + "    @PostMapping\n"
            + "    public void createOrder(String payload) {\n"
            + "        String sql = \"INSERT INTO TRD_ORDERS (id) VALUES (?)\";\n"
            + "        publishEvent(new OrderPlacedEvent());\n"
            + "    }\n"
            + "\n"
            + "    @EventListener\n"
            + "    public void onOrderCancelled(OrderCancelledEvent event) {\n"
            + "    }\n"
            + "\n"
            + "    @KafkaListener(topics = \"trade-executions\")\n"
            + "    public void onExecutionMessage(String msg) {\n"
            + "    }\n"
            + "}\n";

        JavaParser parser = new JavaParser(JavaSourceScanner.createDefaultParserConfig());
        CompilationUnit cu = parser.parse(sourceCode).getResult().orElseThrow();

        AstVisitor visitor = new AstVisitor();
        AstVisitor.VisitContext ctx = new AstVisitor.VisitContext();
        ctx.sourceFile = "/tmp/OrderController.java";

        visitor.visit(cu, ctx);

        // 1. Verify Endpoints
        List<CodeType> endpoints = ctx.types.stream()
            .filter(t -> "ENDPOINT".equals(t.getKind()))
            .toList();
        assertEquals(2, endpoints.size(), "Expected 2 endpoints");
        assertTrue(endpoints.stream().anyMatch(e -> e.getSimpleName().equals("GET /api/v1/orders/{orderId}")),
            "GET /api/v1/orders/{orderId} endpoint found");
        assertTrue(endpoints.stream().anyMatch(e -> e.getSimpleName().equals("POST /api/v1/orders")),
            "POST /api/v1/orders endpoint found");

        // 2. Verify Database Tables
        List<CodeType> tables = ctx.types.stream()
            .filter(t -> "TABLE".equals(t.getKind()))
            .toList();
        assertFalse(tables.isEmpty(), "Database tables should not be empty");
        assertTrue(tables.stream().anyMatch(t -> t.getSimpleName().equals("TRD_ORDERS")),
            "TRD_ORDERS table found");

        // 3. Verify Events
        List<CodeType> events = ctx.types.stream()
            .filter(t -> "EVENT".equals(t.getKind()))
            .toList();
        assertEquals(3, events.size(), "Expected 3 event types");
        assertTrue(events.stream().anyMatch(ev -> ev.getSimpleName().equals("OrderPlacedEvent")),
            "OrderPlacedEvent found");
        assertTrue(events.stream().anyMatch(ev -> ev.getSimpleName().equals("OrderCancelledEvent")),
            "OrderCancelledEvent found");
        assertTrue(events.stream().anyMatch(ev -> ev.getSimpleName().equals("trade-executions")),
            "trade-executions topic found");

        // 4. Verify Relationships
        assertTrue(ctx.relationships.stream().anyMatch(r -> "HANDLED_BY".equals(r.getKind())),
            "HANDLED_BY relationship exists");
        assertTrue(ctx.relationships.stream().anyMatch(r -> "EXPOSES_ENDPOINT".equals(r.getKind())),
            "EXPOSES_ENDPOINT relationship exists");
        assertTrue(ctx.relationships.stream().anyMatch(r -> "MAPS_TO_TABLE".equals(r.getKind())),
            "MAPS_TO_TABLE relationship exists");
        assertTrue(ctx.relationships.stream().anyMatch(r -> "ACCESSES_TABLE".equals(r.getKind())),
            "ACCESSES_TABLE relationship exists");
        assertTrue(ctx.relationships.stream().anyMatch(r -> "WRITES_TABLE".equals(r.getKind())),
            "WRITES_TABLE relationship exists");
        assertTrue(ctx.relationships.stream().anyMatch(r -> "PUBLISHES_EVENT".equals(r.getKind())),
            "PUBLISHES_EVENT relationship exists");
        assertTrue(ctx.relationships.stream().anyMatch(r -> "LISTENS_EVENT".equals(r.getKind())),
            "LISTENS_EVENT relationship exists");
    }
}
