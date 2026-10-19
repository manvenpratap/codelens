package com.codelens.storage;

public class BranchDatabaseTest {

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertFalse(boolean condition, String msg) {
        if (condition) throw new AssertionError("Assertion failed (expected false): " + msg);
    }

    public void testBranchIsolatedStorage() {
        String baseDir = "target/test-codelens-data";
        DatabaseManager dbBranch = DatabaseManager.forBranch(baseDir, "feature/JIRA-1234_payments");
        String dir = dbBranch.getDataDir();

        assertTrue(dir.contains("branches/feature_JIRA-1234_payments"), "Branch name should be sanitized and placed in branches/ subfolder: " + dir);
        assertFalse(dir.endsWith("/"), "Dir should not end with slash");
    }
}
