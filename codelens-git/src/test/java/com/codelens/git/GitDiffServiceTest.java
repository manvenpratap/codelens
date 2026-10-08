package com.codelens.git;

import java.io.File;
import java.util.Objects;

public class GitDiffServiceTest {

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

    public static void main(String[] args) throws Exception {
        new GitDiffServiceTest().testComputeDiffAndIntervalIntersection();
        System.out.println("GitDiffServiceTest passed!");
    }

    public void testComputeDiffAndIntervalIntersection() throws Exception {
        // Test interval intersection logic
        GitDiffService.FileDiffRange range = new GitDiffService.FileDiffRange("src/OrderService.java", "MODIFY");
        range.lineRanges.add(new int[]{20, 30});

        assertTrue(range.intersects(15, 25), "Should intersect [15, 25]");
        assertTrue(range.intersects(25, 35), "Should intersect [25, 35]");
        assertTrue(range.intersects(22, 28), "Should intersect [22, 28]");
        assertTrue(!range.intersects(1, 10), "Should NOT intersect [1, 10]");
        assertTrue(!range.intersects(35, 50), "Should NOT intersect [35, 50]");

        // Test running against current repo
        GitDiffService service = new GitDiffService();
        GitDiffService.GitDiffReport report = service.computeDiff(new File("."), "HEAD~1", "HEAD");

        assertNotNull(report, "Diff report should not be null");
        assertNotNull(report.repoPath, "Repo path should not be null");
        assertNotNull(report.baseRef, "Base ref should not be null");
        assertNotNull(report.headRef, "Head ref should not be null");
    }
}
