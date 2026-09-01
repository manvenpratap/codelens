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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Walks a Java source directory, parses .java files with bounded multi-threading,
 * and streams discovered entities in chunks to a {@link BatchConsumer} to keep
 * Java heap memory usage strictly bounded even for 50,000+ classes.
 */
public class JavaSourceScanner {

    private static final Logger log = LoggerFactory.getLogger(JavaSourceScanner.class);

    /** Holds summary metrics and (optionally) in-memory entities for small scans. */
    public static class ScanResult {
        public final List<CodePackage>      packages      = new ArrayList<>();
        public final List<CodeType>         types         = new ArrayList<>();
        public final List<CodeField>        fields        = new ArrayList<>();
        public final List<CodeMethod>       methods       = new ArrayList<>();
        public final List<CodeRelationship> relationships = new ArrayList<>();
        public int totalFiles;
        public int parsedFiles;
        public int errorFiles;
        public int typesFound;
        public int methodsFound;
        public int fieldsFound;
        public int relationshipsFound;
    }

    /** Callback invoked after each file is processed: (processedCount, totalCount, filePath). */
    @FunctionalInterface
    public interface ProgressCallback {
        void onFile(int processed, int total, String filePath);
    }

    /** Consumer invoked when a batch of parsed entities is ready to be flushed to DB/storage. */
    @FunctionalInterface
    public interface BatchConsumer {
        void onBatch(List<CodePackage> packages,
                     List<CodeType> types,
                     List<CodeField> fields,
                     List<CodeMethod> methods,
                     List<CodeRelationship> relationships) throws Exception;
    }

    // ─────────────────────────────────────────────────────────────────────────

    public static final List<String> DEFAULT_EXCLUDE_PATTERNS = List.of(
        "target", "build", ".mvn", ".git", ".gradle", ".idea", ".vscode", "node_modules", "bin", "out", "dist"
    );

    private static final int PARSER_THREADS = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 8));

    private static final ThreadLocal<JavaParser> THREAD_PARSER = ThreadLocal.withInitial(() -> {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        cfg.setStoreTokens(false);
        cfg.setAttributeComments(false);
        return new JavaParser(cfg);
    });

    public JavaSourceScanner() {}

    /**
     * Helper to compile wildcard and folder exclude patterns into PathMatchers.
     */
    public static List<PathMatcher> compileMatchers(List<String> patterns) {
        List<PathMatcher> matchers = new ArrayList<>();
        if (patterns == null) return matchers;
        for (String pat : patterns) {
            if (pat == null || pat.isBlank()) continue;
            String trimmed = pat.trim().replace('\\', '/');
            try {
                if (trimmed.startsWith("glob:") || trimmed.startsWith("regex:")) {
                    matchers.add(FileSystems.getDefault().getPathMatcher(trimmed));
                } else if (trimmed.contains("*") || trimmed.contains("?") || trimmed.contains("{") || trimmed.contains("[")) {
                    if (!trimmed.startsWith("**/") && !trimmed.startsWith("/")) {
                        matchers.add(FileSystems.getDefault().getPathMatcher("glob:**/" + trimmed));
                    }
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + trimmed));
                } else {
                    // Folder or file path / segment
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:**/" + trimmed + "/**"));
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:**/" + trimmed));
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + trimmed + "/**"));
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + trimmed));
                }
            } catch (Exception e) {
                log.warn("Invalid exclude pattern '{}': {}", pat, e.getMessage());
            }
        }
        return matchers;
    }

    /**
     * Checks if a relative path matches any folder name, pattern, or path matcher.
     */
    public static boolean isExcluded(Path relativePath, List<PathMatcher> matchers, List<String> rawPatterns) {
        if (rawPatterns == null || rawPatterns.isEmpty()) return false;
        String relStr = relativePath.toString().replace('\\', '/');
        if (relStr.startsWith("/")) relStr = relStr.substring(1);

        // Fast segment exact match for folder names like "target", ".mvn", etc.
        String[] segments = relStr.split("/");
        for (String seg : segments) {
            for (String raw : rawPatterns) {
                if (raw != null && !raw.contains("/") && !raw.contains("*") && !raw.contains("?")
                        && seg.equalsIgnoreCase(raw.trim())) {
                    return true;
                }
            }
        }

        // PathMatcher checks for globs and path patterns
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relativePath) || matcher.matches(Paths.get(relStr))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Performs a full directory scan with default exclude patterns.
     */
    public ScanResult scan(String sourceRoot, ProgressCallback progressCallback) throws IOException {
        return scan(sourceRoot, DEFAULT_EXCLUDE_PATTERNS, null, progressCallback);
    }

    /**
     * Performs a full directory scan with custom folder/file exclude patterns.
     */
    public ScanResult scan(String sourceRoot, List<String> excludePatterns, ProgressCallback progressCallback) throws IOException {
        return scan(sourceRoot, excludePatterns, null, progressCallback);
    }

    /**
     * Performs a full directory scan with custom folder/file exclude patterns and streaming BatchConsumer.
     */
    public ScanResult scan(String sourceRoot,
                           List<String> excludePatterns,
                           BatchConsumer batchConsumer,
                           ProgressCallback progressCallback) throws IOException {
        Path root = Paths.get(sourceRoot);
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Source root does not exist: " + sourceRoot);
        }

        List<String> effectiveExcludes = (excludePatterns != null && !excludePatterns.isEmpty())
            ? excludePatterns
            : DEFAULT_EXCLUDE_PATTERNS;
        List<PathMatcher> matchers = compileMatchers(effectiveExcludes);

        // ── Phase 1: collect all .java files (skipping excluded folders) ─────
        List<Path> javaFiles = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                Path relativePath = root.relativize(dir);
                if (isExcluded(relativePath, matchers, effectiveExcludes)) {
                    log.debug("Skipping excluded directory subtree: {}", relativePath);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    Path relativePath = root.relativize(file);
                    if (!isExcluded(relativePath, matchers, effectiveExcludes)) {
                        javaFiles.add(file);
                    } else {
                        log.debug("Skipping excluded Java file: {}", relativePath);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("Cannot access file: {} — {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        log.info("Found {} Java files under {} (after applying {} exclude patterns)",
            javaFiles.size(), sourceRoot, effectiveExcludes.size());

        // ── Phase 2: bounded parallel parsing & streaming ─────────────────────
        ScanResult result    = new ScanResult();
        result.totalFiles    = javaFiles.size();
        AtomicInteger count  = new AtomicInteger(0);
        AtomicInteger parsed = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        AtomicInteger totalTypes = new AtomicInteger(0);
        AtomicInteger totalMethods = new AtomicInteger(0);
        AtomicInteger totalFields = new AtomicInteger(0);
        AtomicInteger totalRels = new AtomicInteger(0);

        ConcurrentMap<String, CodePackage> packageMap = new ConcurrentHashMap<>();
        ConcurrentMap<String, AtomicInteger> typesPerPkg = new ConcurrentHashMap<>();

        ExecutorService pool = new ThreadPoolExecutor(
            PARSER_THREADS, PARSER_THREADS,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(PARSER_THREADS * 4),
            new ThreadFactory() {
                private int idx = 0;
                @Override
                public synchronized Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "codelens-ast-worker-" + (++idx));
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // Partition files into work chunks to reduce task submission overhead
        int chunkSize = Math.max(10, Math.min(100, javaFiles.size() / (PARSER_THREADS * 8) + 1));
        List<List<Path>> chunks = new ArrayList<>();
        for (int i = 0; i < javaFiles.size(); i += chunkSize) {
            chunks.add(javaFiles.subList(i, Math.min(i + chunkSize, javaFiles.size())));
        }

        List<Future<?>> futures = new ArrayList<>();
        Object flushLock = new Object();

        for (List<Path> chunk : chunks) {
            futures.add(pool.submit(() -> {
                JavaParser parser = THREAD_PARSER.get();
                AstVisitor visitor = new AstVisitor();

                List<CodePackage> batchPkgs = new ArrayList<>();
                List<CodeType> batchTypes = new ArrayList<>();
                List<CodeField> batchFields = new ArrayList<>();
                List<CodeMethod> batchMethods = new ArrayList<>();
                List<CodeRelationship> batchRels = new ArrayList<>();

                for (Path javaFile : chunk) {
                    try {
                        AstVisitor.VisitContext ctx = new AstVisitor.VisitContext();
                        ctx.sourceFile = javaFile.toAbsolutePath().toString();

                        ParseResult<CompilationUnit> parseResult = parser.parse(javaFile);

                        if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                            CompilationUnit cu = parseResult.getResult().get();
                            visitor.visit(cu, ctx);

                            for (CodePackage pkg : ctx.packages) {
                                packageMap.computeIfAbsent(pkg.getFqn(), k -> {
                                    CodePackage cp = new CodePackage(pkg.getFqn());
                                    cp.setFileCount(0);
                                    return cp;
                                }).setFileCount(packageMap.get(pkg.getFqn()).getFileCount() + 1);
                            }

                            for (CodeType t : ctx.types) {
                                if (t.getPackageFqn() != null) {
                                    typesPerPkg.computeIfAbsent(t.getPackageFqn(), k -> new AtomicInteger(0)).incrementAndGet();
                                }
                            }

                            batchPkgs.addAll(ctx.packages);
                            batchTypes.addAll(ctx.types);
                            batchFields.addAll(ctx.fields);
                            batchMethods.addAll(ctx.methods);
                            batchRels.addAll(ctx.relationships);

                            totalTypes.addAndGet(ctx.types.size());
                            totalMethods.addAndGet(ctx.methods.size());
                            totalFields.addAndGet(ctx.fields.size());
                            totalRels.addAndGet(ctx.relationships.size());
                            parsed.incrementAndGet();
                        } else {
                            log.warn("Parse errors in {}: {}", javaFile, parseResult.getProblems());
                            errors.incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.error("Failed to parse {}: {}", javaFile, e.getMessage());
                        errors.incrementAndGet();
                    }

                    int done = count.incrementAndGet();
                    if (progressCallback != null) {
                        progressCallback.onFile(done, javaFiles.size(), javaFile.toString());
                    }
                }

                // Flush chunk batch
                try {
                    synchronized (flushLock) {
                        if (batchConsumer != null) {
                            batchConsumer.onBatch(batchPkgs, batchTypes, batchFields, batchMethods, batchRels);
                        } else {
                            result.packages.addAll(batchPkgs);
                            result.types.addAll(batchTypes);
                            result.fields.addAll(batchFields);
                            result.methods.addAll(batchMethods);
                            result.relationships.addAll(batchRels);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error flushing parsed batch to consumer", e);
                }
            }));
        }

        pool.shutdown();
        try {
            for (Future<?> f : futures) {
                f.get();
            }
            pool.awaitTermination(1, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("Scanning pool error", e);
            throw new IOException("Scanning execution interrupted", e);
        }

        // ── Phase 3: populate final package summary ───────────────────────────
        List<CodePackage> finalPackages = new ArrayList<>();
        for (Map.Entry<String, CodePackage> entry : packageMap.entrySet()) {
            CodePackage pkg = entry.getValue();
            AtomicInteger tCount = typesPerPkg.get(entry.getKey());
            pkg.setTypeCount(tCount != null ? tCount.get() : 0);
            finalPackages.add(pkg);
        }

        if (batchConsumer != null && !finalPackages.isEmpty()) {
            try {
                synchronized (flushLock) {
                    batchConsumer.onBatch(finalPackages, Collections.emptyList(), Collections.emptyList(),
                                          Collections.emptyList(), Collections.emptyList());
                }
            } catch (Exception e) {
                log.error("Error flushing final package statistics", e);
            }
        }

        result.parsedFiles = parsed.get();
        result.errorFiles = errors.get();
        result.typesFound = totalTypes.get();
        result.methodsFound = totalMethods.get();
        result.fieldsFound = totalFields.get();
        result.relationshipsFound = totalRels.get();

        if (result.packages.isEmpty()) {
            result.packages.addAll(finalPackages);
        }

        log.info("Scan complete: {} types, {} methods, {} fields, {} relationships, {} errors across {} files",
            result.typesFound, result.methodsFound, result.fieldsFound,
            result.relationshipsFound, result.errorFiles, result.parsedFiles);

        return result;
    }
}

