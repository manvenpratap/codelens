package com.codelens.app;

import com.codelens.api.CodeLensServer;
import com.codelens.core.model.CodeLensConfig;
import com.codelens.storage.DatabaseManager;
import com.codelens.storage.LuceneService;
import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * CodeLens application entry point.
 *
 * JVM system properties (all optional):
 *   -Dcodelens.config=./codelens.conf configuration file path
 *   -Dcodelens.data=./codelens-data   data directory for H2 + Lucene files
 *   -Dcodelens.port=7878              HTTP server port
 *
 * Usage:
 *   java -jar codelens-app.jar
 *   java -Dcodelens.port=9090 -jar codelens-app.jar
 *   java -Dcodelens.config=/path/to/codelens.conf -jar codelens-app.jar
 */
public class Application {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "false");

        // ── Locate and load deployment configuration ───────────────────────────
        File configFile = resolveConfigFile(args);
        CodeLensConfig config = null;
        if (configFile != null && configFile.exists()) {
            try {
                config = CodeLensConfig.loadFromFile(configFile);
                System.out.printf("  Loaded deployment configuration from %s%n", configFile.getAbsolutePath());
            } catch (Exception e) {
                System.err.printf("  Warning: Failed to load config from %s: %s%n", configFile.getAbsolutePath(), e.getMessage());
            }
        }
        if (config == null) {
            config = new CodeLensConfig();
        }

        String dataDir = System.getProperty("codelens.data", config.getDataDir());
        int port = (System.getProperty("codelens.port") != null)
            ? Integer.parseInt(System.getProperty("codelens.port"))
            : config.getPort();

        config.setPort(port);
        config.setDataDir(dataDir);
        if (configFile == null) {
            configFile = new File("./codelens.conf");
        }

        // ── CLI Scan mode: java -jar codelens-app.jar scan [sourcePath] ────────
        if (args.length > 0 && "scan".equalsIgnoreCase(args[0])) {
            String targetPath = args.length > 1 ? args[1] : (config.getDefaultScanPath() != null && !config.getDefaultScanPath().isBlank() ? config.getDefaultScanPath() : "./sample-project/src/main/java");
            System.out.printf("Starting CLI scan for %s...%n", targetPath);
            DatabaseManager db = new DatabaseManager(dataDir);
            db.initialize();
            db.clearAll();
            LuceneService lucene = new LuceneService(dataDir);
            lucene.initialize();
            com.codelens.storage.EntityDao dao = new com.codelens.storage.EntityDao(db);

            com.codelens.parser.JavaSourceScanner scanner = new com.codelens.parser.JavaSourceScanner();
            db.prepareForBulkLoad();
            lucene.prepareIndexRebuild();

            com.codelens.core.model.ScanProgress progress = new com.codelens.core.model.ScanProgress(com.codelens.core.model.ScanProgress.Status.SCANNING);
            progress.setSourcePath(targetPath);
            progress.setStartTime(System.currentTimeMillis());

            com.codelens.parser.JavaSourceScanner.ScanResult result = scanner.scan(
                targetPath,
                java.util.Collections.emptyList(),
                (pkgs, types, fields, methods, rels, fileMetas) -> {
                    dao.batchInsertChunkFast(pkgs, types, fields, methods, rels, fileMetas);
                    lucene.addBatch(types, methods, fields);
                },
                (done, total, file) -> {
                    if (done % 100 == 0 || done == total) {
                        System.out.printf("  Processed %d/%d files...%n", done, total);
                    }
                },
                () -> false
            );

            lucene.finishIndexRebuild();
            db.finishBulkLoad();

            // Invalidate disk graph cache so subsequent runs don't serve stale layouts
            File graphCacheDir = new File(dataDir, "graph-cache");
            if (graphCacheDir.exists()) {
                File[] cacheFiles = graphCacheDir.listFiles((d, name) -> name.endsWith(".json"));
                if (cacheFiles != null) {
                    for (File f : cacheFiles) {
                        f.delete();
                    }
                }
            }

            progress.setTotalFiles(result.totalFiles);
            progress.setProcessedFiles(result.totalFiles);
            progress.setParsedFiles(result.parsedFiles);
            progress.setTypesFound(result.typesFound);
            progress.setMethodsFound(result.methodsFound);
            progress.setFieldsFound(result.fieldsFound);
            progress.setRelationshipsFound(result.relationshipsFound);
            progress.setStatus(com.codelens.core.model.ScanProgress.Status.COMPLETE);
            progress.setCurrentPhase("Complete");
            progress.setCurrentDetail("Ready");
            progress.setEndTime(System.currentTimeMillis());
            dao.saveScanMeta(progress);

            lucene.close();
            db.close();
            System.out.printf("CLI scan complete: %d files, %d types, %d methods, %d relationships in %.2fs%n",
                result.parsedFiles, result.typesFound, result.methodsFound, result.relationshipsFound,
                (System.currentTimeMillis() - progress.getStartTime()) / 1000.0);
            return;
        }

        // ── CLI Story mode: java -jar codelens-app.jar story [query] ──────────
        if (args.length > 0 && "story".equalsIgnoreCase(args[0])) {
            if (tryExecuteCliViaHttp(port, args)) {
                return;
            }
            DatabaseManager db = new DatabaseManager(dataDir);
            db.initialize();
            com.codelens.storage.EntityDao dao = new com.codelens.storage.EntityDao(db);
            List<com.codelens.core.model.CodeType> types = dao.findAllTypes();
            List<com.codelens.core.model.CodeMethod> methods = dao.findAllMethods();
            com.codelens.analysis.CallGraphAnalyzer callGraph = new com.codelens.analysis.CallGraphAnalyzer();
            List<String> mFqns = new java.util.ArrayList<>(methods.size());
            for (com.codelens.core.model.CodeMethod m : methods) mFqns.add(m.getFqn());
            callGraph.rebuild(mFqns, dao::streamCallRelationships);

            com.codelens.analysis.StoryEngine storyEngine = new com.codelens.analysis.StoryEngine();
            String query = args.length > 1 ? args[1] : null;

            if (query != null && !query.isBlank() && (query.contains(".") || query.contains("("))) {
                com.codelens.analysis.StoryEngine.Storyline detail = storyEngine.getStorylineByFqn(query, types, methods, callGraph.getCallGraph());
                if (detail == null) {
                    System.err.printf("No storyline found starting at %s%n", query);
                } else {
                    printStorylineDetailCli(detail);
                }
            } else {
                List<com.codelens.analysis.StoryEngine.StorylineSummary> list = storyEngine.discoverStorylines(types, methods, callGraph.getCallGraph());
                if (query != null && !query.isBlank()) {
                    String qLower = query.toLowerCase(java.util.Locale.ROOT);
                    list = list.stream().filter(s -> (s.title != null && s.title.toLowerCase(java.util.Locale.ROOT).contains(qLower)) || (s.executiveSummary != null && s.executiveSummary.toLowerCase(java.util.Locale.ROOT).contains(qLower))).toList();
                }
                printStorylinesListCli(list);
            }
            db.close();
            return;
        }

        // ── CLI Trace mode: java -jar codelens-app.jar trace <fqn> ────────────
        if (args.length > 0 && "trace".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                System.err.println("Usage: java -jar codelens-app.jar trace <method-or-class-fqn>");
                return;
            }
            if (tryExecuteCliViaHttp(port, args)) {
                return;
            }
            DatabaseManager db = new DatabaseManager(dataDir);
            db.initialize();
            com.codelens.storage.EntityDao dao = new com.codelens.storage.EntityDao(db);
            List<com.codelens.core.model.CodeType> types = dao.findAllTypes();
            List<com.codelens.core.model.CodeMethod> methods = dao.findAllMethods();
            com.codelens.analysis.CallGraphAnalyzer callGraph = new com.codelens.analysis.CallGraphAnalyzer();
            List<String> mFqns = new java.util.ArrayList<>(methods.size());
            for (com.codelens.core.model.CodeMethod m : methods) mFqns.add(m.getFqn());
            callGraph.rebuild(mFqns, dao::streamCallRelationships);

            com.codelens.analysis.StoryEngine storyEngine = new com.codelens.analysis.StoryEngine();
            com.codelens.analysis.StoryEngine.Storyline detail = storyEngine.getStorylineByFqn(args[1], types, methods, callGraph.getCallGraph());
            if (detail != null) {
                printStorylineDetailCli(detail);
            } else {
                System.err.printf("No trace execution flow found for %s%n", args[1]);
            }
            db.close();
            return;
        }

        // ── CLI What-If / Impact mode: java -jar codelens-app.jar impact <fqn> ──
        if (args.length > 0 && ("what-if".equalsIgnoreCase(args[0]) || "whatif".equalsIgnoreCase(args[0]) || "impact".equalsIgnoreCase(args[0]))) {
            if (args.length < 2) {
                System.err.println("Usage: java -jar codelens-app.jar impact <method-fqn>");
                return;
            }
            if (tryExecuteCliViaHttp(port, args)) {
                return;
            }
            DatabaseManager db = new DatabaseManager(dataDir);
            db.initialize();
            com.codelens.storage.EntityDao dao = new com.codelens.storage.EntityDao(db);
            List<com.codelens.core.model.CodeType> types = dao.findAllTypes();
            List<com.codelens.core.model.CodeMethod> methods = dao.findAllMethods();
            com.codelens.analysis.CallGraphAnalyzer callGraph = new com.codelens.analysis.CallGraphAnalyzer();
            List<String> mFqns = new java.util.ArrayList<>(methods.size());
            for (com.codelens.core.model.CodeMethod m : methods) mFqns.add(m.getFqn());
            callGraph.rebuild(mFqns, dao::streamCallRelationships);

            com.codelens.analysis.StoryEngine storyEngine = new com.codelens.analysis.StoryEngine();
            com.codelens.analysis.StoryEngine.ChangeImpactStory impact = storyEngine.analyzeChangeImpact(args[1], types, methods, callGraph.getCallGraph());
            printWhatIfCli(impact);
            db.close();
            return;
        }

        // ── CLI Ask mode: java -jar codelens-app.jar ask "<question>" ─────────
        if (args.length > 0 && "ask".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                System.err.println("Usage: java -jar codelens-app.jar ask \"<natural language question>\"");
                return;
            }
            if (tryExecuteCliViaHttp(port, args)) {
                return;
            }
            DatabaseManager db = new DatabaseManager(dataDir);
            db.initialize();
            com.codelens.storage.EntityDao dao = new com.codelens.storage.EntityDao(db);
            List<com.codelens.core.model.CodeType> types = dao.findAllTypes();
            List<com.codelens.core.model.CodeMethod> methods = dao.findAllMethods();
            com.codelens.analysis.CallGraphAnalyzer callGraph = new com.codelens.analysis.CallGraphAnalyzer();
            List<String> mFqns = new java.util.ArrayList<>(methods.size());
            for (com.codelens.core.model.CodeMethod m : methods) mFqns.add(m.getFqn());
            callGraph.rebuild(mFqns, dao::streamCallRelationships);

            com.codelens.analysis.StoryEngine storyEngine = new com.codelens.analysis.StoryEngine();
            List<com.codelens.analysis.StoryEngine.StorylineSummary> storylines = storyEngine.discoverStorylines(types, methods, callGraph.getCallGraph());

            com.codelens.analysis.AiGroundingService ai = new com.codelens.analysis.AiGroundingService(
                config.getAiProvider(), config.getAiModel(), config.getAiEndpoint(), config.getAiApiKey());
            com.codelens.analysis.AiGroundingService.GroundedAnswer answer = ai.askQuestion(args[1], types, methods, callGraph.getCallGraph(), storylines);
            printAiAskCli(answer);
            db.close();
            return;
        }

        // ── CLI Explain mode: java -jar codelens-app.jar explain <fqn> ────────
        if (args.length > 0 && "explain".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                System.err.println("Usage: java -jar codelens-app.jar explain <symbol-or-class-fqn>");
                return;
            }
            if (tryExecuteCliViaHttp(port, args)) {
                return;
            }
            DatabaseManager db = new DatabaseManager(dataDir);
            db.initialize();
            com.codelens.storage.EntityDao dao = new com.codelens.storage.EntityDao(db);
            List<com.codelens.core.model.CodeType> types = dao.findAllTypes();
            List<com.codelens.core.model.CodeMethod> methods = dao.findAllMethods();
            com.codelens.analysis.CallGraphAnalyzer callGraph = new com.codelens.analysis.CallGraphAnalyzer();
            List<String> mFqns = new java.util.ArrayList<>(methods.size());
            for (com.codelens.core.model.CodeMethod m : methods) mFqns.add(m.getFqn());
            callGraph.rebuild(mFqns, dao::streamCallRelationships);

            com.codelens.analysis.StoryEngine storyEngine = new com.codelens.analysis.StoryEngine();
            List<com.codelens.analysis.StoryEngine.StorylineSummary> storylines = storyEngine.discoverStorylines(types, methods, callGraph.getCallGraph());

            com.codelens.analysis.AiGroundingService ai = new com.codelens.analysis.AiGroundingService(
                config.getAiProvider(), config.getAiModel(), config.getAiEndpoint(), config.getAiApiKey());
            com.codelens.analysis.AiGroundingService.SymbolExplanation explanation = ai.explainSymbol(args[1], types, methods, callGraph.getCallGraph(), storylines);
            printAiExplainCli(explanation);
            db.close();
            return;
        }

        printBanner(port);

        long maxMem = Runtime.getRuntime().maxMemory();
        if (maxMem < 1_000_000_000L) {
            System.err.printf("%n  [ADVISORY] Maximum JVM heap is configured at %d MB. For large enterprise codebases,%n" +
                              "  consider increasing heap space to optimize report precomputation throughput:%n" +
                              "  java -Xms512m -Xmx2g -XX:+UseG1GC -jar codelens-app.jar%n%n", maxMem / (1024 * 1024));
        }

        // ── Initialise storage layer ──────────────────────────────────────────
        DatabaseManager db = new DatabaseManager(dataDir);
        db.initialize();

        LuceneService lucene = new LuceneService(dataDir);
        lucene.initialize();

        // ── Start HTTP server ─────────────────────────────────────────────────
        CodeLensServer server = new CodeLensServer(db, lucene, port);
        server.setConfig(config, configFile);
        server.start();

        System.out.printf("%n  CodeLens is running → http://localhost:%d%n%n", port);

        // ── Auto-launch default browser ─────────────────────────────────────────
        openBrowser(port);

        // ── Graceful shutdown hook ─────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n  Shutting down CodeLens…");
            server.stop();
            lucene.close();
            db.close();
            System.out.println("  Goodbye.");
        }, "codelens-shutdown"));

        // Keep main thread alive
        Thread.currentThread().join();
    }

    private static void openBrowser(int port) {
        if ("true".equalsIgnoreCase(System.getProperty("codelens.no-browser")) ||
            "true".equalsIgnoreCase(System.getenv("CODELENS_NO_BROWSER"))) {
            return;
        }

        String url = "http://localhost:" + port;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        } catch (Throwable ignored) {}

        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", url});
            } else if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", url});
            } else if (os.contains("nix") || os.contains("nux")) {
                Runtime.getRuntime().exec(new String[]{"xdg-open", url});
            }
        } catch (Throwable ignored) {}
    }

    private static void printBanner(int port) {
        System.out.println();
        System.out.println("  ╔═══════════════════════════════════════╗");
        System.out.println("  ║   ██████╗ ██████╗ ██████╗ ███████╗    ║");
        System.out.println("  ║  ██╔════╝██╔═══██╗██╔══██╗██╔════╝    ║");
        System.out.println("  ║  ██║     ██║   ██║██║  ██║█████╗      ║");
        System.out.println("  ║  ██║     ██║   ██║██║  ██║██╔══╝      ║");
        System.out.println("  ║  ╚██████╗╚██████╔╝██████╔╝███████╗    ║");
        System.out.println("  ║   ╚═════╝ ╚═════╝ ╚═════╝ ╚══════╝    ║");
        System.out.println("  ║                L E N S                ║");
        System.out.println("  ║    Java Codebase Intelligence v1.0    ║");
        System.out.println("  ╚═══════════════════════════════════════╝");
        System.out.printf( "  Starting on port %d…%n", port);
    }

    private static File resolveConfigFile(String[] args) {
        String propConfig = System.getProperty("codelens.config");
        if (propConfig != null && !propConfig.isBlank()) {
            return new File(propConfig);
        }

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if ("--config".equals(args[i]) && i + 1 < args.length) {
                    return new File(args[i + 1]);
                }
            }
        }

        File defaultConf = new File("./codelens.conf");
        if (defaultConf.exists()) return defaultConf;

        File dataConf = new File("./codelens-data/codelens.conf");
        if (dataConf.exists()) return dataConf;

        return null;
    }

    private static boolean tryExecuteCliViaHttp(int port, String[] args) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(400))
                .build();
            String query = args.length > 1 ? args[1] : null;
            String mode = args[0].toLowerCase(java.util.Locale.ROOT);
            String url;
            if ("trace".equals(mode)) {
                if (query == null || query.isBlank()) return false;
                url = "http://127.0.0.1:" + port + "/api/storyline?fqn=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            } else if ("what-if".equals(mode) || "whatif".equals(mode) || "impact".equals(mode)) {
                if (query == null || query.isBlank()) return false;
                url = "http://127.0.0.1:" + port + "/api/storyline/what-if?fqn=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            } else if ("ask".equals(mode)) {
                if (query == null || query.isBlank()) return false;
                url = "http://127.0.0.1:" + port + "/api/ai/ask?q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            } else if ("explain".equals(mode)) {
                if (query == null || query.isBlank()) return false;
                url = "http://127.0.0.1:" + port + "/api/ai/explain?fqn=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                if (query != null && (query.contains(".") || query.contains("("))) {
                    url = "http://127.0.0.1:" + port + "/api/storyline?fqn=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
                } else {
                    url = "http://127.0.0.1:" + port + "/api/storylines" + (query != null ? "?q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8) : "");
                }
            }

            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(6))
                .header("Accept", "application/json")
                .GET()
                .build();
            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
                if (root.has("answerText")) {
                    printAiAskJsonCli(root);
                } else if (root.has("detailedNarrative") || root.has("targetFqn")) {
                    printAiExplainJsonCli(root);
                } else if (root.has("storylines")) {
                    com.fasterxml.jackson.databind.JsonNode list = root.get("storylines");
                    System.out.printf("%n=== CodeStory: Discovered Repository Storylines (%d) ===%n%n", list.size());
                    for (int i = 0; i < list.size(); i++) {
                        var s = list.get(i);
                        System.out.printf("[%2d] %-34s | %-16s | %d steps | %s%n",
                            i + 1, s.path("title").asText(), s.path("category").asText(), s.path("stepCount").asInt(), s.path("entryClass").asText());
                        System.out.printf("     -> %s%n%n", s.path("executiveSummary").asText());
                    }
                } else if (root.has("steps")) {
                    System.out.printf("%n=== Storyline: %s [%s] ===%n", root.path("title").asText(), root.path("category").asText());
                    System.out.printf("Executive Narrative: %s%n%n", root.path("executiveSummary").asText());
                    System.out.println("Execution Sequence (Interactive Storyline):");
                    com.fasterxml.jackson.databind.JsonNode steps = root.path("steps");
                    for (int i = 0; i < steps.size(); i++) {
                        var step = steps.get(i);
                        String src = step.path("sourceFile").asText("");
                        String shortFile = src.contains("/") ? src.substring(src.lastIndexOf('/') + 1) : src;
                        System.out.printf("  %d. [%-14s] %s.%s (%s:%d)%n",
                            step.path("stepIndex").asInt(i + 1), step.path("roleLabel").asText(),
                            step.path("classSimpleName").asText(), step.path("simpleName").asText(),
                            shortFile, step.path("startLine").asInt());
                        System.out.printf("     Action: %s%n", step.path("narrativeAction").asText());
                    }
                    System.out.println();
                } else if (root.has("impactNarrative")) {
                    printWhatIfJsonCli(root);
                }
                return true;
            }
        } catch (Exception ignored) {
            // Server offline or port unreachable, fall back to direct DB manager
        }
        return false;
    }

    private static void printStorylinesListCli(List<com.codelens.analysis.StoryEngine.StorylineSummary> list) {
        System.out.printf("%n=== CodeStory: Discovered Repository Storylines (%d) ===%n%n", list.size());
        for (int i = 0; i < list.size(); i++) {
            var s = list.get(i);
            System.out.printf("[%2d] %-34s | %-16s | %d steps | %s%n",
                i + 1, s.title, s.category, s.stepCount, s.entryClass);
            System.out.printf("     -> %s%n%n", s.executiveSummary);
        }
    }

    private static void printStorylineDetailCli(com.codelens.analysis.StoryEngine.Storyline s) {
        System.out.printf("%n=== Storyline: %s [%s] ===%n", s.title, s.category);
        System.out.printf("Executive Narrative: %s%n%n", s.executiveSummary);
        System.out.println("Execution Sequence (Interactive Storyline):");
        for (var step : s.steps) {
            String shortFile = step.sourceFile != null && step.sourceFile.contains("/")
                ? step.sourceFile.substring(step.sourceFile.lastIndexOf('/') + 1)
                : (step.sourceFile != null ? step.sourceFile : "source");
            System.out.printf("  %d. [%-14s] %s.%s (%s:%d)%n",
                step.stepIndex, step.roleLabel, step.classSimpleName, step.simpleName, shortFile, step.startLine);
            System.out.printf("     Action: %s%n", step.narrativeAction);
        }
        System.out.println();
    }

    private static void printWhatIfCli(com.codelens.analysis.StoryEngine.ChangeImpactStory impact) {
        System.out.printf("%n=== CodeStory What-If Analysis: %s ===%n%n", impact.targetMethod);
        System.out.printf("Impact Narrative: %s%n%n", impact.impactNarrative);
        System.out.printf("Directly Affected Workflows (%d):%n", impact.affectedStorylines.size());
        for (String s : impact.affectedStorylines) {
            System.out.printf("  • %s%n", s);
        }
        System.out.printf("%nUpstream Callers (%d):%n", impact.upstreamCallers.size());
        for (String c : impact.upstreamCallers) {
            System.out.printf("  ↑ %s%n", c);
        }
        System.out.printf("%nDownstream Components (%d):%n", impact.downstreamCallees.size());
        for (String d : impact.downstreamCallees) {
            System.out.printf("  ↓ %s%n", d);
        }
        System.out.printf("%nCovering Automated Tests (%d):%n", impact.coveringTests.size());
        for (String t : impact.coveringTests) {
            System.out.printf("  ✓ %s%n", t);
        }
        System.out.println();
    }

    private static void printWhatIfJsonCli(com.fasterxml.jackson.databind.JsonNode root) {
        System.out.printf("%n=== CodeStory What-If Analysis: %s ===%n%n", root.path("targetMethod").asText());
        System.out.printf("Impact Narrative: %s%n%n", root.path("impactNarrative").asText());
        var aff = root.path("affectedStorylines");
        System.out.printf("Directly Affected Workflows (%d):%n", aff.size());
        for (int i = 0; i < aff.size(); i++) {
            System.out.printf("  • %s%n", aff.get(i).asText());
        }
        var up = root.path("upstreamCallers");
        System.out.printf("%nUpstream Callers (%d):%n", up.size());
        for (int i = 0; i < up.size(); i++) {
            System.out.printf("  ↑ %s%n", up.get(i).asText());
        }
        var down = root.path("downstreamCallees");
        System.out.printf("%nDownstream Components (%d):%n", down.size());
        for (int i = 0; i < down.size(); i++) {
            System.out.printf("  ↓ %s%n", down.get(i).asText());
        }
        var tests = root.path("coveringTests");
        System.out.printf("%nCovering Automated Tests (%d):%n", tests.size());
        for (int i = 0; i < tests.size(); i++) {
            System.out.printf("  ✓ %s%n", tests.get(i).asText());
        }
        System.out.println();
    }

    private static void printAiAskCli(com.codelens.analysis.AiGroundingService.GroundedAnswer a) {
        System.out.println();
        System.out.println("=== CodeStory Grounded Q&A ===");
        System.out.printf("Question: %s%n", a.question);
        System.out.printf("Engine:   %s (%s) | %dms latency%n%n", a.provider, a.model, a.responseTimeMs);
        System.out.println("[Answer]");
        System.out.println(a.answerText);
        System.out.println();
        if (a.citations != null && !a.citations.isEmpty()) {
            System.out.printf("[Verified Source Citations (%d)]%n", a.citations.size());
            for (int i = 0; i < a.citations.size(); i++) {
                var c = a.citations.get(i);
                System.out.printf("  %d. [%-12s] %s (%s:%d)%n", i + 1, c.role != null ? c.role : "Evidence", c.symbol, c.file, c.line);
            }
            System.out.println();
        }
        if (a.relevantStorylines != null && !a.relevantStorylines.isEmpty()) {
            System.out.printf("[Related Storylines (%d)]%n", a.relevantStorylines.size());
            for (String s : a.relevantStorylines) {
                System.out.printf("  • %s%n", s);
            }
            System.out.println();
        }
    }

    private static void printAiAskJsonCli(com.fasterxml.jackson.databind.JsonNode root) {
        System.out.println();
        System.out.println("=== CodeStory Grounded Q&A ===");
        System.out.printf("Question: %s%n", root.path("question").asText());
        System.out.printf("Engine:   %s (%s) | %dms latency%n%n",
            root.path("provider").asText(), root.path("model").asText(), root.path("responseTimeMs").asLong());
        System.out.println("[Answer]");
        System.out.println(root.path("answerText").asText());
        System.out.println();
        var citations = root.path("citations");
        if (citations.isArray() && citations.size() > 0) {
            System.out.printf("[Verified Source Citations (%d)]%n", citations.size());
            for (int i = 0; i < citations.size(); i++) {
                var c = citations.get(i);
                System.out.printf("  %d. [%-12s] %s (%s:%d)%n",
                    i + 1, c.path("role").asText("Evidence"), c.path("symbol").asText(), c.path("file").asText(), c.path("line").asInt());
            }
            System.out.println();
        }
        var stories = root.path("relevantStorylines");
        if (stories.isArray() && stories.size() > 0) {
            System.out.printf("[Related Storylines (%d)]%n", stories.size());
            for (int i = 0; i < stories.size(); i++) {
                System.out.printf("  • %s%n", stories.get(i).asText());
            }
            System.out.println();
        }
    }

    private static void printAiExplainCli(com.codelens.analysis.AiGroundingService.SymbolExplanation exp) {
        System.out.println();
        System.out.printf("=== CodeStory Symbol Explanation: %s ===%n", exp.simpleName);
        System.out.printf("Target: %s [%s]%n", exp.targetFqn, exp.role);
        System.out.printf("Summary: %s%n%n", exp.summary);
        System.out.println("[Architectural Narrative]");
        System.out.println(exp.detailedNarrative != null ? exp.detailedNarrative : exp.summary);
        System.out.println();
        System.out.printf("[Architectural Coupling]%n");
        System.out.printf("Incoming Callers (%d):%n", exp.incomingCallersCount);
        for (String c : exp.callers) {
            System.out.printf("  ↑ %s%n", c);
        }
        System.out.printf("%nOutgoing Calls (%d):%n", exp.outgoingCallsCount);
        for (String d : exp.callees) {
            System.out.printf("  ↓ %s%n", d);
        }
        if (exp.affectedStorylines != null && !exp.affectedStorylines.isEmpty()) {
            System.out.printf("%n[Storyline Involvements (%d)]%n", exp.affectedStorylines.size());
            for (String s : exp.affectedStorylines) {
                System.out.printf("  • %s%n", s);
            }
        }
        if (exp.citations != null && !exp.citations.isEmpty()) {
            System.out.printf("%n[Verified Citations (%d)]%n", exp.citations.size());
            for (var c : exp.citations) {
                System.out.printf("  • %s:%d [%s]%n", c.file, c.line, c.symbol);
            }
        }
        System.out.println();
    }

    private static void printAiExplainJsonCli(com.fasterxml.jackson.databind.JsonNode root) {
        System.out.println();
        System.out.printf("=== CodeStory Symbol Explanation: %s ===%n", root.path("simpleName").asText());
        System.out.printf("Target: %s [%s]%n", root.path("targetFqn").asText(), root.path("role").asText());
        System.out.printf("Summary: %s%n%n", root.path("summary").asText());
        System.out.println("[Architectural Narrative]");
        System.out.println(root.path("detailedNarrative").asText(root.path("summary").asText()));
        System.out.println();
        var callers = root.path("callers");
        System.out.printf("[Architectural Coupling]%n");
        System.out.printf("Incoming Callers (%d):%n", root.path("incomingCallersCount").asInt());
        for (int i = 0; i < callers.size(); i++) {
            System.out.printf("  ↑ %s%n", callers.get(i).asText());
        }
        var callees = root.path("callees");
        System.out.printf("%nOutgoing Calls (%d):%n", root.path("outgoingCallsCount").asInt());
        for (int i = 0; i < callees.size(); i++) {
            System.out.printf("  ↓ %s%n", callees.get(i).asText());
        }
        var stories = root.path("affectedStorylines");
        if (stories.isArray() && stories.size() > 0) {
            System.out.printf("%n[Storyline Involvements (%d)]%n", stories.size());
            for (int i = 0; i < stories.size(); i++) {
                System.out.printf("  • %s%n", stories.get(i).asText());
            }
        }
        var citations = root.path("citations");
        if (citations.isArray() && citations.size() > 0) {
            System.out.printf("%n[Verified Citations (%d)]%n", citations.size());
            for (int i = 0; i < citations.size(); i++) {
                var c = citations.get(i);
                System.out.printf("  • %s:%d [%s]%n", c.path("file").asText(), c.path("line").asInt(), c.path("symbol").asText());
            }
        }
        System.out.println();
    }
}
