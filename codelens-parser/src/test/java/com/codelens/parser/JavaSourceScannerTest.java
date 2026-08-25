package com.codelens.parser;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public class JavaSourceScannerTest {

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertFalse(boolean condition, String msg) {
        if (condition) throw new AssertionError("Assertion failed (expected false): " + msg);
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + " (expected: " + expected + ", got: " + actual + ")");
    }

    public void testIsExcludedWithFolderNames() {
        List<String> raw = List.of("target", "build", ".mvn", ".git");
        List<PathMatcher> matchers = JavaSourceScanner.compileMatchers(raw);

        // Subtree paths
        assertTrue(JavaSourceScanner.isExcluded(Paths.get("target"), matchers, raw), "target folder");
        assertTrue(JavaSourceScanner.isExcluded(Paths.get("target/classes/Foo.class"), matchers, raw), "target classes");
        assertTrue(JavaSourceScanner.isExcluded(Paths.get("moduleA/target/generated-sources/Bar.java"), matchers, raw), "module target");
        assertTrue(JavaSourceScanner.isExcluded(Paths.get(".mvn/wrapper/MavenWrapperDownloader.java"), matchers, raw), "mvn wrapper");
        assertTrue(JavaSourceScanner.isExcluded(Paths.get(".git/objects/pack"), matchers, raw), "git objects");
        assertTrue(JavaSourceScanner.isExcluded(Paths.get("sub/build/libs/app.jar"), matchers, raw), "build libs");

        // Non-excluded normal paths
        assertFalse(JavaSourceScanner.isExcluded(Paths.get("src/main/java/com/example/Service.java"), matchers, raw), "src main");
        assertFalse(JavaSourceScanner.isExcluded(Paths.get("core/model/User.java"), matchers, raw), "core model");
    }

    public void testIsExcludedWithGlobPatterns() {
        List<String> raw = List.of("**/test/**", "*Test.java", "generated-*/**");
        List<PathMatcher> matchers = JavaSourceScanner.compileMatchers(raw);

        assertTrue(JavaSourceScanner.isExcluded(Paths.get("src/test/java/MyTest.java"), matchers, raw), "src test java");
        assertTrue(JavaSourceScanner.isExcluded(Paths.get("com/example/OrderServiceTest.java"), matchers, raw), "order service test");
        assertTrue(JavaSourceScanner.isExcluded(Paths.get("generated-sources/annotations/Model.java"), matchers, raw), "generated sources");

        assertFalse(JavaSourceScanner.isExcluded(Paths.get("src/main/java/MyService.java"), matchers, raw), "my service");
        assertFalse(JavaSourceScanner.isExcluded(Paths.get("com/example/OrderService.java"), matchers, raw), "order service");
    }

    public void testScanExcludesFolders(Path tempDir) throws IOException {
        // Create sample project tree with main, test, and target folders
        Path mainPkg = tempDir.resolve("src/main/java/com/app");
        Path testPkg = tempDir.resolve("src/test/java/com/app");
        Path targetDir = tempDir.resolve("target/generated-sources");
        Path mvnDir = tempDir.resolve(".mvn/wrapper");

        Files.createDirectories(mainPkg);
        Files.createDirectories(testPkg);
        Files.createDirectories(targetDir);
        Files.createDirectories(mvnDir);

        Files.writeString(mainPkg.resolve("AppService.java"),
            "package com.app;\npublic class AppService { public void run() {} }");
        Files.writeString(testPkg.resolve("AppServiceTest.java"),
            "package com.app;\npublic class AppServiceTest { public void test() {} }");
        Files.writeString(targetDir.resolve("GeneratedEntity.java"),
            "package com.app.gen;\npublic class GeneratedEntity {}");
        Files.writeString(mvnDir.resolve("MavenWrapperDownloader.java"),
            "public class MavenWrapperDownloader {}");

        JavaSourceScanner scanner = new JavaSourceScanner();

        // 1. Scan with default excludes (ignores target and .mvn, but includes main and test)
        JavaSourceScanner.ScanResult res1 = scanner.scan(tempDir.toString(), null);
        assertEquals(2, res1.totalFiles, "Should scan AppService.java and AppServiceTest.java");
        assertTrue(res1.types.stream().anyMatch(t -> t.getSimpleName().equals("AppService")), "AppService found");
        assertTrue(res1.types.stream().anyMatch(t -> t.getSimpleName().equals("AppServiceTest")), "AppServiceTest found");

        // 2. Scan with custom exclude including tests
        List<String> customExcludes = List.of("target", ".mvn", "**/test/**");
        JavaSourceScanner.ScanResult res2 = scanner.scan(tempDir.toString(), customExcludes, null);
        assertEquals(1, res2.totalFiles, "Should only scan AppService.java");
        assertTrue(res2.types.stream().anyMatch(t -> t.getSimpleName().equals("AppService")), "AppService found");
        assertFalse(res2.types.stream().anyMatch(t -> t.getSimpleName().equals("AppServiceTest")), "AppServiceTest excluded");
    }

    public static void main(String[] args) throws Exception {
        JavaSourceScannerTest test = new JavaSourceScannerTest();
        System.out.println("Running testIsExcludedWithFolderNames...");
        test.testIsExcludedWithFolderNames();
        System.out.println("Running testIsExcludedWithGlobPatterns...");
        test.testIsExcludedWithGlobPatterns();
        System.out.println("Running testScanExcludesFolders...");
        Path tempDir = Files.createTempDirectory("codelens_scanner_test");
        try {
            test.testScanExcludesFolders(tempDir);
        } finally {
            // cleanup temp files
            Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) throws IOException {
                    Files.delete(f);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        System.out.println("ALL JAVA SOURCE SCANNER EXCLUSION TESTS PASSED SUCCESSFULLY!");
    }
}
