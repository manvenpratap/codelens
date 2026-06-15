package com.codelens.app;

import com.codelens.api.CodeLensServer;
import com.codelens.storage.DatabaseManager;
import com.codelens.storage.LuceneService;

/**
 * CodeLens application entry point.
 *
 * JVM system properties (all optional):
 *   -Dcodelens.data=./codelens-data   data directory for H2 + Lucene files
 *   -Dcodelens.port=7878              HTTP server port
 *
 * Usage:
 *   java -jar codelens-app-1.0.0.jar
 *   java -Dcodelens.port=9090 -jar codelens-app-1.0.0.jar
 */
public class Application {

    public static void main(String[] args) throws Exception {
        String dataDir = System.getProperty("codelens.data", "./codelens-data");
        int    port    = Integer.parseInt(System.getProperty("codelens.port", "7878"));

        printBanner(port);

        // ── Initialise storage layer ──────────────────────────────────────────
        DatabaseManager db = new DatabaseManager(dataDir);
        db.initialize();

        LuceneService lucene = new LuceneService(dataDir);
        lucene.initialize();

        // ── Start HTTP server ─────────────────────────────────────────────────
        CodeLensServer server = new CodeLensServer(db, lucene, port);
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
}
