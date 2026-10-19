package com.codelens.api;

import com.codelens.analysis.CallGraphAnalyzer;
import com.codelens.analysis.CodeReviewEngine;
import com.codelens.core.model.ReviewFinding;

import java.util.List;
import java.util.concurrent.*;

public class SecurityAndConcurrencyTest {

    private void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    public void testAiEndpointValidation() {
        // Safe endpoints
        assertTrue(CodeLensServer.isValidAiEndpoint("http://localhost:11434/api/generate"), "Localhost Ollama must be allowed");
        assertTrue(CodeLensServer.isValidAiEndpoint("http://127.0.0.1:11434/api/generate"), "127.0.0.1 Ollama must be allowed");
        assertTrue(CodeLensServer.isValidAiEndpoint("https://api.openai.com/v1/chat/completions"), "Public HTTPS APIs must be allowed");
        assertTrue(CodeLensServer.isValidAiEndpoint(null), "Null or blank endpoints are safe default fallbacks");
        assertTrue(CodeLensServer.isValidAiEndpoint(""), "Empty endpoints are safe default fallbacks");

        // Disallowed / SSRF endpoints
        assertFalse(CodeLensServer.isValidAiEndpoint("http://169.254.169.254/latest/meta-data/"), "AWS Cloud metadata endpoint must be blocked");
        assertFalse(CodeLensServer.isValidAiEndpoint("ftp://localhost:21"), "Non-HTTP protocols must be blocked");
        assertFalse(CodeLensServer.isValidAiEndpoint("file:///etc/passwd"), "File protocol must be blocked");
    }

    public void testCodeReviewEngineThreadSafety() throws Exception {
        CodeReviewEngine engine = new CodeReviewEngine();

        String fileA = """
            package com.test;
            public class ClassA {
                public void methodA() {
                    String s = "hello";
                    s.trim();
                }
            }
            """;

        String fileB = """
            package com.test;
            public class ClassB {
                public void methodB() {
                    String x = "world";
                    x.trim();
                }
            }
            """;

        int threads = 8;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final boolean even = (i % 2 == 0);
            exec.submit(() -> {
                try {
                    for (int j = 0; j < 50; j++) {
                        String source = even ? fileA : fileB;
                        List<ReviewFinding> findings = engine.reviewSnippet(source, null, null);
                        for (ReviewFinding f : findings) {
                            String snippet = f.getSourceSnippet();
                            if (even && snippet.contains("ClassB")) {
                                throw new AssertionError("Race condition: ClassA review contained ClassB snippet: " + snippet);
                            }
                            if (!even && snippet.contains("ClassA")) {
                                throw new AssertionError("Race condition: ClassB review contained ClassA snippet: " + snippet);
                            }
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out awaiting parallel review execution");
        exec.shutdownNow();

        if (!errors.isEmpty()) {
            throw new AssertionError("Thread safety test failed with error: " + errors.peek().getMessage(), errors.peek());
        }
    }

    public void testConfigPosixPermissions() throws Exception {
        com.codelens.core.model.CodeLensConfig config = new com.codelens.core.model.CodeLensConfig();
        config.setAiApiKey("sk-secret-test-key-12345");
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("codelens-conf-test-", ".conf");
        try {
            config.saveToFile(tempFile.toFile());
            assertTrue(java.nio.file.Files.exists(tempFile), "Config file must exist");
            if (java.nio.file.Files.getFileAttributeView(tempFile, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = java.nio.file.Files.getPosixFilePermissions(tempFile);
                String permStr = java.nio.file.attribute.PosixFilePermissions.toString(perms);
                assertTrue(permStr.equals("rw-------"), "Config file permissions must be owner-only (rw-------); got: " + permStr);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }
}
