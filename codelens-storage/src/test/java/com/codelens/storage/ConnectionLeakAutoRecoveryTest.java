package com.codelens.storage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;

public class ConnectionLeakAutoRecoveryTest {

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertFalse(boolean condition, String msg) {
        if (condition) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertNotNull(Object obj, String msg) {
        if (obj == null) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + " (expected: " + expected + ", got: " + actual + ")");
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        file.delete();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running ConnectionLeakAutoRecoveryTest...");
        testConnectionLeakAutoRecovery();
        testManualSweepLeaksEndpoint();
        System.out.println("All ConnectionLeakAutoRecoveryTest tests passed successfully!");
    }

    public static void testConnectionLeakAutoRecovery() throws Exception {
        Path tempDir = Files.createTempDirectory("codelens-leak-test-");
        DatabaseManager db = new DatabaseManager(tempDir.toString());
        try {
            db.initialize();
            db.setLeakThresholdMs(500); // 500ms threshold for test

            // 1. Borrow a connection and intentionally leak it (do not close)
            Connection leakedConn = db.getConnection();
            assertNotNull(leakedConn, "Leaked connection should not be null");
            assertFalse(leakedConn.isClosed(), "Leaked connection should be open");

            // Run a query to confirm it works
            try (Statement s = leakedConn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT 1")) {
                assertTrue(rs.next(), "SELECT 1 should return a row");
                assertEquals(1, rs.getInt(1), "SELECT 1 should return 1");
            }

            // Verify it is tracked in activeLeases
            Map<String, Object> poolStats = db.getPoolStats();
            assertEquals(1, (Integer) poolStats.get("activeTracked"), "Should have 1 active tracked connection");

            // 2. Wait for the leak threshold to elapse
            Thread.sleep(600);

            // 3. Trigger check and recovery
            int recovered = db.checkAndRecoverConnectionLeaks();
            assertEquals(1, recovered, "Should auto-recover exactly 1 leaked connection");

            // 4. Verify diagnostics reflect the recovered leak
            Map<String, Object> statsAfter = db.getPoolStats();
            assertEquals(1, (Integer) statsAfter.get("leaksRecovered"), "leaksRecovered count should be 1");
            assertEquals(0, (Integer) statsAfter.get("activeTracked"), "activeTracked count should now be 0");

            Map<String, Object> diag = db.getDiagnostics();
            @SuppressWarnings("unchecked")
            Map<String, Object> leakDiag = (Map<String, Object>) diag.get("leakRecovery");
            assertNotNull(leakDiag, "leakRecovery diagnostics should be present");
            assertEquals(1, (Integer) leakDiag.get("totalRecovered"), "totalRecovered should be 1");
            assertNotNull(leakDiag.get("lastRecovered"), "lastRecovered details should be present");

            // 5. Subsequent attempts to use the leaked connection must throw an explanatory SQLException
            boolean threw = false;
            try {
                leakedConn.createStatement();
            } catch (SQLException ex) {
                threw = true;
                assertTrue(ex.getMessage().contains("auto-recovered and evicted"),
                    "Exception message should mention auto-recovery: " + ex.getMessage());
            }
            assertTrue(threw, "Using auto-recovered connection should throw SQLException");

            // 6. Verify pool remains healthy and new connections can be borrowed
            try (Connection freshConn = db.getConnection();
                 Statement s = freshConn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT 1")) {
                assertTrue(rs.next(), "Fresh connection should execute successfully");
            }

            System.out.println("  ✓ testConnectionLeakAutoRecovery passed");
        } finally {
            db.close();
            deleteRecursively(tempDir.toFile());
        }
    }

    public static void testManualSweepLeaksEndpoint() throws Exception {
        Path tempDir = Files.createTempDirectory("codelens-sweep-test-");
        DatabaseManager db = new DatabaseManager(tempDir.toString());
        try {
            db.initialize();
            db.setLeakThresholdMs(300);

            Connection conn = db.getConnection();
            Thread.sleep(400);

            Map<String, Object> sweepResult = db.sweepConnectionLeaks();
            assertTrue(Boolean.TRUE.equals(sweepResult.get("success")), "Sweep should succeed");
            assertEquals(1, (Integer) sweepResult.get("evictedCount"), "Sweep should evict 1 leaked connection");
            assertEquals(1, (Integer) sweepResult.get("totalLeaksRecovered"), "totalLeaksRecovered should be 1");

            // Second sweep when clean should report 0
            Map<String, Object> sweepResult2 = db.sweepConnectionLeaks();
            assertEquals(0, (Integer) sweepResult2.get("evictedCount"), "Second sweep should evict 0");

            System.out.println("  ✓ testManualSweepLeaksEndpoint passed");
        } finally {
            db.close();
            deleteRecursively(tempDir.toFile());
        }
    }
}
