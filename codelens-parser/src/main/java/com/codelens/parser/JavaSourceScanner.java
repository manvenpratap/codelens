package com.codelens.parser;

import com.codelens.core.model.*;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Walks a Java source directory, parses every .java file with JavaParser,
 * and collects all discovered entities into a single {@link ScanResult}.
 *
 * Usage:
 * <pre>
 *   JavaSourceScanner scanner = new JavaSourceScanner();
 *   ScanResult result = scanner.scan("/path/to/src", progressCallback);
 * </pre>
 */
public class JavaSourceScanner {

    private static final Logger log = LoggerFactory.getLogger(JavaSourceScanner.class);

    /** Holds all entities extracted from a full scan. */
    public static class ScanResult {
        public final List<CodePackage>      packages      = new ArrayList<>();
        public final List<CodeType>         types         = new ArrayList<>();
        public final List<CodeField>        fields        = new ArrayList<>();
        public final List<CodeMethod>       methods       = new ArrayList<>();
        public final List<CodeRelationship> relationships = new ArrayList<>();
        public int totalFiles;
        public int parsedFiles;
        public int errorFiles;
    }

    /** Callback invoked after each file is processed: (processedCount, totalCount, filePath). */
    @FunctionalInterface
    public interface ProgressCallback {
        void onFile(int processed, int total, String filePath);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private final JavaParser parser;

    public JavaSourceScanner() {
        ParserConfiguration cfg = new ParserConfiguration();
        // Java 17 language level; falls back gracefully for older syntax
        cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.parser = new JavaParser(cfg);
    }

    /**
     * Performs a full directory scan.
     *
     * @param sourceRoot      absolute path to the root of the Java source tree
     * @param progressCallback invoked once per file (may be null)
     * @return ScanResult with all discovered entities
     */
    public ScanResult scan(String sourceRoot, ProgressCallback progressCallback) throws IOException {
        Path root = Paths.get(sourceRoot);
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Source root does not exist: " + sourceRoot);
        }

        // ── Phase 1: collect all .java files ─────────────────────────────────
        List<Path> javaFiles = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    javaFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("Cannot access file: {} — {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        log.info("Found {} Java files under {}", javaFiles.size(), sourceRoot);

        // ── Phase 2: parse each file ──────────────────────────────────────────
        ScanResult result   = new ScanResult();
        result.totalFiles   = javaFiles.size();
        AstVisitor visitor  = new AstVisitor();
        AtomicInteger count = new AtomicInteger(0);

        // Track seen packages to avoid duplicates
        Set<String> seenPackages = new HashSet<>();

        for (Path javaFile : javaFiles) {
            try {
                AstVisitor.VisitContext ctx = new AstVisitor.VisitContext();
                ctx.sourceFile = javaFile.toAbsolutePath().toString();

                ParseResult<CompilationUnit> parseResult = parser.parse(javaFile);

                if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                    CompilationUnit cu = parseResult.getResult().get();
                    visitor.visit(cu, ctx);

                    // Merge packages (deduplicate)
                    for (CodePackage pkg : ctx.packages) {
                        if (seenPackages.add(pkg.getFqn())) {
                            result.packages.add(pkg);
                        } else {
                            // Increment file count on existing entry
                            result.packages.stream()
                                .filter(p -> p.getFqn().equals(pkg.getFqn()))
                                .findFirst()
                                .ifPresent(p -> p.setFileCount(p.getFileCount() + 1));
                        }
                    }

                    result.types.addAll(ctx.types);
                    result.fields.addAll(ctx.fields);
                    result.methods.addAll(ctx.methods);
                    result.relationships.addAll(ctx.relationships);
                    result.parsedFiles++;

                } else {
                    log.warn("Parse errors in {}: {}", javaFile, parseResult.getProblems());
                    result.errorFiles++;
                }

            } catch (Exception e) {
                log.error("Failed to parse {}: {}", javaFile, e.getMessage());
                result.errorFiles++;
            }

            int done = count.incrementAndGet();
            if (progressCallback != null) {
                progressCallback.onFile(done, javaFiles.size(), javaFile.toString());
            }
        }

        // ── Phase 3: update type counts on packages ────────────────────────
        Map<String, Integer> typesPerPkg = new HashMap<>();
        for (CodeType t : result.types) {
            typesPerPkg.merge(t.getPackageFqn(), 1, Integer::sum);
        }
        for (CodePackage pkg : result.packages) {
            pkg.setTypeCount(typesPerPkg.getOrDefault(pkg.getFqn(), 0));
            pkg.setFileCount(
                Math.max(pkg.getFileCount(),
                         (int) javaFiles.stream()
                             .filter(f -> isFileInPackage(f, root, pkg.getFqn()))
                             .count()));
        }

        log.info("Scan complete: {} types, {} methods, {} fields, {} relationships, {} errors",
            result.types.size(), result.methods.size(), result.fields.size(),
            result.relationships.size(), result.errorFiles);

        return result;
    }

    /** Rough check: does this file sit in the directory corresponding to the given package? */
    private boolean isFileInPackage(Path file, Path root, String pkgFqn) {
        String pkgPath = pkgFqn.replace('.', '/');
        String fileStr = file.toString().replace('\\', '/');
        return fileStr.contains(pkgPath + "/");
    }
}
