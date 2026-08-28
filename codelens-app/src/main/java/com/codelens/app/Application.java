package com.codelens.app;

import com.codelens.api.CodeLensServer;
import com.codelens.core.model.CodeLensConfig;
import com.codelens.storage.DatabaseManager;
import com.codelens.storage.LuceneService;
import java.io.File;

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

        // ── Graceful shutdown hook ─────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n  Shutting down CodeLens…");
            server.stop();
            lucene.close();
            db.close();
            System.out.println("  Goodbye.");
        }, "codelens-shutdown"));
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
