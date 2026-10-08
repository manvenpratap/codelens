package com.codelens.api;

import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class ApiTrackerTest {

    private void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " - Expected: " + expected + ", Actual: " + actual);
        }
    }

    public void testCatalogRegistration() {
        ApiTracker tracker = new ApiTracker();
        Map<String, Object> summary = tracker.getSummary();
        assertEquals(97, summary.get("totalEndpoints"), "Should register all 97 API endpoints");
        assertEquals(0L, summary.get("totalRequests"), "Initial total requests should be 0");

        List<Map<String, Object>> endpoints = tracker.getEndpoints();
        assertEquals(97, endpoints.size(), "Endpoint catalog should contain 97 entries");
    }

    public void testDirectTelemetryRecording() {
        ApiTracker tracker = new ApiTracker();
        tracker.recordRequestStart("GET", "/api/processes");
        tracker.recordRequestEnd("GET", "/api/processes", 200, 15);

        Map<String, Object> summary = tracker.getSummary();
        assertEquals(1L, summary.get("totalRequests"), "Total requests should be 1");
        assertEquals(15.0, summary.get("avgLatencyMs"), "Avg latency should be 15.0");
        assertEquals(1L, summary.get("status2xx"), "2xx count should be 1");

        List<Map<String, Object>> endpoints = tracker.getEndpoints();
        Map<String, Object> procEp = endpoints.stream()
                .filter(e -> "/api/processes".equals(e.get("path")) && "GET".equals(e.get("method")))
                .findFirst()
                .orElse(null);

        assertTrue(procEp != null, "Endpoint /api/processes must exist in catalog");
        assertEquals(1L, procEp.get("calls"), "Endpoint calls should be 1");
        assertEquals(15.0, procEp.get("avgLatencyMs"), "Endpoint avg latency should be 15.0");
        assertEquals(200, procEp.get("lastStatus"), "Endpoint last status should be 200");
    }

    public void testParameterizedRouteWithJavalin() throws Exception {
        ApiTracker tracker = new ApiTracker();
        Javalin app = Javalin.create().start(0);
        int port = app.port();

        app.before(ctx -> {
            if (ctx.path().startsWith("/api")) {
                ctx.attribute("startTime", System.currentTimeMillis());
                tracker.recordRequestStart(ctx.method().name(), ctx.path());
            }
        });

        app.after(ctx -> {
            if (ctx.path().startsWith("/api")) {
                Long start = ctx.attribute("startTime");
                long duration = start != null ? (System.currentTimeMillis() - start) : 0;
                String matchedPath = null;
                try {
                    matchedPath = ctx.endpointHandlerPath();
                } catch (Throwable ignored) {}
                if (matchedPath == null || matchedPath.isBlank() || matchedPath.startsWith("No handler")) {
                    matchedPath = ctx.path();
                }
                tracker.recordRequestEnd(ctx.method().name(), matchedPath, ctx.status().getCode(), duration);
            }
        });

        app.get("/api/processes", ctx -> ctx.result("ok"));
        app.get("/api/types/{id}", ctx -> ctx.result("type"));

        HttpClient client = HttpClient.newHttpClient();
        client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/processes")).build(),
                HttpResponse.BodyHandlers.ofString());
        client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/types/OrderEntity")).build(),
                HttpResponse.BodyHandlers.ofString());

        app.stop();

        List<Map<String, Object>> endpoints = tracker.getEndpoints();

        Map<String, Object> procEp = endpoints.stream()
                .filter(e -> "/api/processes".equals(e.get("path")) && "GET".equals(e.get("method")))
                .findFirst()
                .orElseThrow();
        assertEquals(1L, procEp.get("calls"), "Calls to /api/processes must be 1");
        assertEquals(200, procEp.get("lastStatus"), "Last status of /api/processes must be 200");

        Map<String, Object> typeEp = endpoints.stream()
                .filter(e -> "/api/types/{id}".equals(e.get("path")) && "GET".equals(e.get("method")))
                .findFirst()
                .orElseThrow();
        assertEquals(1L, typeEp.get("calls"), "Parameterized /api/types/{id} calls must be 1");
        assertEquals(200, typeEp.get("lastStatus"), "Last status of /api/types/{id} must be 200");
    }
}
