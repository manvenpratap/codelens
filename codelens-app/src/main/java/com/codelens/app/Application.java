package com.codelens.app;

import com.codelens.api.CodeLensServer;
import com.codelens.core.model.CodeLensConfig;
import com.codelens.storage.DatabaseManager;
import com.codelens.storage.LuceneService;
import java.awt.Desktop;
import java.io.File;
import java.net.URI;

/**
 * CodeLens application entry point.
 *
 * JVM system properties (all optional):
 *   -Dcodelens.config=./codelens.conf configuration file path
 *   -Dcodelens.data=./codelens-data   data directory for H2 + Lucene files
 *   -Dcodelens.port=7878              HTTP server port
 *
 * Usage:
 *   java -jar codelens-app-1.0.0.jar
 *   java -Dcodelens.port=9090 -jar codelens-app-1.0.0.jar
 *   java -Dcodelens.config=/path/to/codelens.conf -jar codelens-app-1.0.0.jar
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
                new com.codelens.parser.JavaSourceScanner.BatchConsumer() {
                    @Override
                    public void onBatch(java.util.List<com.codelens.core.model.CodePackage> pkgs,
                                        java.util.List<com.codelens.core.model.CodeType> types,
                                        java.util.List<com.codelens.core.model.CodeField> fields,
                                        java.util.List<com.codelens.core.model.CodeMethod> methods,
                                        java.util.List<com.codelens.core.model.CodeRelationship> rels) throws Exception {
                        onBatch(pkgs, types, fields, methods, rels, java.util.Collections.emptyList());
                    }
                    @Override
                    public void onBatch(java.util.List<com.codelens.core.model.CodePackage> pkgs,
                                        java.util.List<com.codelens.core.model.CodeType> types,
                                        java.util.List<com.codelens.core.model.CodeField> fields,
                                        java.util.List<com.codelens.core.model.CodeMethod> methods,
                                        java.util.List<com.codelens.core.model.CodeRelationship> rels,
                                        java.util.List<com.codelens.core.model.FileMeta> fileMetas) throws Exception {
                        dao.batchInsertChunkFast(pkgs, types, fields, methods, rels, fileMetas);
                        lucene.addBatch(types, methods, fields);
                    }
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

        printBanner(port);

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
        System.out.println("  ║   ██████╗ ██████╗ ██████╗ ███████╗   ║");
        System.out.println("  ║  ██╔════╝██╔═══██╗██╔══██╗██╔════╝   ║");
        System.out.println("  ║  ██║     ██║   ██║██║  ██║█████╗     ║");
        System.out.println("  ║  ██║     ██║   ██║██║  ██║██╔══╝     ║");
        System.out.println("  ║  ╚██████╗╚██████╔╝██████╔╝███████╗   ║");
        System.out.println("  ║   ╚═════╝ ╚═════╝ ╚═════╝ ╚══════╝   ║");
        System.out.println("  ║        L E N S                        ║");
        System.out.println("  ║   Java Codebase Intelligence v1.0     ║");
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
}
