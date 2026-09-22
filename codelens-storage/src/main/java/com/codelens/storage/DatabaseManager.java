package com.codelens.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;

/**
 * Manages the embedded H2 database lifecycle.
 *
 * ADR: H2 in file mode (not in-memory) so the index persists across server
 * restarts. AUTO_SERVER=FALSE is intentional — no TCP server is started.
 * HikariCP provides a small connection pool for concurrent read requests.
 *
 * The full DDL (schema creation) is idempotent — safe to run on every startup.
 */
public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final String dataDir;
    private HikariDataSource dataSource;

    // Connection tracking and leak auto-recovery
    private final java.util.concurrent.ConcurrentHashMap<Connection, ConnectionLease> activeLeases = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger totalLeaksDetected = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger totalLeaksRecovered = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile java.util.Map<String, Object> lastRecoveredLeak = null;
    private volatile long leakThresholdMs = 60_000L; // 60s default leak timeout
    private java.util.concurrent.ScheduledExecutorService leakWatchdog;

    public DatabaseManager(String dataDir) {
        this.dataDir = dataDir;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    private HikariConfig createHikariConfig() {
        HikariConfig cfg = new HikariConfig();
        // DB_CLOSE_DELAY=-1: keep H2 alive as long as the JVM runs.
        // CACHE_SIZE=524288 (512MB cache), PAGE_SIZE=4096 (standard B-Tree page size).
        // COMPRESS=TRUE: enable page compression (4x-5x disk reduction for text/FQNs).
        // AUTO_COMPACT_FILL_RATE=90: enable active MVStore chunk compaction and space reuse.
        // RETENTION_TIME=0: immediately release old transaction page versions.
        // TRACE_LEVEL_FILE=0: disable .trace.db file creation and disk bloat.
        // LOCK_TIMEOUT=30000: 30s timeout to handle heavy I/O gracefully.
        cfg.setJdbcUrl("jdbc:h2:file:" + dataDir + "/codelens_db"
                     + ";AUTO_SERVER=FALSE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000;CACHE_SIZE=524288;PAGE_SIZE=4096;DEFRAG_ALWAYS=TRUE;COMPRESS=TRUE;AUTO_COMPACT_FILL_RATE=90;RETENTION_TIME=0;TRACE_LEVEL_FILE=0");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(20);
        cfg.setMinimumIdle(4);
        cfg.setConnectionTimeout(60_000);
        cfg.setValidationTimeout(5_000);
        cfg.setMaxLifetime(1800_000);
        cfg.setLeakDetectionThreshold(60_000); // 60s - HikariCP log threshold matching auto-recovery watchdog
        cfg.setPoolName("CodeLens-H2");
        return cfg;
    }

    private synchronized void startLeakWatchdog() {
        if (leakWatchdog != null && !leakWatchdog.isShutdown()) {
            return;
        }
        leakWatchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CodeLens-DbLeakWatchdog");
            t.setDaemon(true);
            return t;
        });
        leakWatchdog.scheduleWithFixedDelay(this::checkAndRecoverConnectionLeaks, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Initialises the connection pool and creates all tables. */
    public void initialize() throws Exception {
        Files.createDirectories(Paths.get(dataDir));
        HikariConfig cfg = createHikariConfig();
        dataSource = new HikariDataSource(cfg);

        createSchema();
        ensureSecondaryIndexes();
        startLeakWatchdog();
        log.info("H2 database initialised at {}/codelens_db (indexes verified, leak auto-recovery active)", dataDir);
    }

    /** Self-healing check: verify secondary indexes exist; rebuild if dropped by a previous crash during bulk load. */
    public void ensureSecondaryIndexes() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'METHODS' AND INDEX_NAME = 'IDX_METHODS_TYPE'")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    log.warn("Secondary index idx_methods_type missing (prior crash during bulk load). Self-healing indexes now...");
                    finishBulkLoad();
                    return;
                }
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'RELATIONSHIPS' AND INDEX_NAME = 'IDX_RELS_CALLS_COVERING'")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_rels_calls_covering ON relationships(kind, id, from_entity_fqn, to_entity_fqn)");
                }
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'RELATIONSHIPS' AND INDEX_NAME = 'IDX_RELS_FIELDS_COVERING'")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_rels_fields_covering ON relationships(kind, id, to_entity_fqn, from_entity_fqn)");
                }
            }
        } catch (Exception e) {
            log.warn("Could not verify secondary indexes: {}", e.getMessage());
        }
    }

    public void close() {
        if (leakWatchdog != null) {
            leakWatchdog.shutdownNow();
        }
        activeLeases.clear();
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }

    /** Expose a connection from the pool wrapped with active lease tracking and leak auto-recovery. */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("DatabaseManager data source is not initialized or has been closed.");
        }

        // Emergency starvation pre-check: if pool is saturated and threads are awaiting, run leak recovery immediately
        com.zaxxer.hikari.HikariPoolMXBean mx = dataSource.getHikariPoolMXBean();
        if (mx != null && mx.getThreadsAwaitingConnection() > 0 && mx.getActiveConnections() >= dataSource.getMaximumPoolSize()) {
            checkAndRecoverConnectionLeaks();
        }

        Connection rawConn = dataSource.getConnection();
        ConnectionLease lease = new ConnectionLease(rawConn);
        activeLeases.put(rawConn, lease);

        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
            DatabaseManager.class.getClassLoader(),
            new Class<?>[]{ Connection.class },
            new TrackedConnectionHandler(rawConn, lease, this)
        );
    }

    public void unregisterLease(Connection rawConn) {
        if (rawConn != null) {
            activeLeases.remove(rawConn);
        }
    }

    public void setLeakThresholdMs(long ms) {
        this.leakThresholdMs = ms;
    }

    public long getLeakThresholdMs() {
        return leakThresholdMs;
    }

    /** Returns connection pool diagnostics for Process Hub and health monitoring. */
    public java.util.Map<String, Object> getPoolStats() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (dataSource != null && !dataSource.isClosed()) {
            com.zaxxer.hikari.HikariPoolMXBean mx = dataSource.getHikariPoolMXBean();
            if (mx != null) {
                m.put("activeConnections", mx.getActiveConnections());
                m.put("idleConnections", mx.getIdleConnections());
                m.put("totalConnections", mx.getTotalConnections());
                m.put("threadsAwaiting", mx.getThreadsAwaitingConnection());
            } else {
                m.put("activeConnections", 0);
                m.put("idleConnections", dataSource.getMinimumIdle());
                m.put("totalConnections", dataSource.getMaximumPoolSize());
                m.put("threadsAwaiting", 0);
            }
            m.put("maxPoolSize", dataSource.getMaximumPoolSize());
            m.put("poolName", dataSource.getPoolName());
            m.put("leaksRecovered", totalLeaksRecovered.get());
            m.put("activeTracked", activeLeases.size());
        }
        return m;
    }

    /** Returns comprehensive database diagnostics including storage size, pool status, table counts, and index health. */
    public java.util.Map<String, Object> getDiagnostics() {
        java.util.Map<String, Object> diag = new java.util.LinkedHashMap<>();

        // 1. File & Storage Info
        java.nio.file.Path dbFile = java.nio.file.Paths.get(dataDir, "codelens_db.mv.db");
        long sizeBytes = 0;
        try {
            if (java.nio.file.Files.exists(dbFile)) {
                sizeBytes = java.nio.file.Files.size(dbFile);
            }
        } catch (Exception ignored) {}
        double sizeMb = Math.round((sizeBytes / (1024.0 * 1024.0)) * 10.0) / 10.0;

        diag.put("filePath", dbFile.toAbsolutePath().toString());
        diag.put("fileSizeBytes", sizeBytes);
        diag.put("fileSizeMb", sizeMb);
        diag.put("storageMode", "Embedded MVStore (File-backed)");

        // 2. Pool stats
        diag.put("pool", getPoolStats());

        // 3. Engine & Connection Ping
        String h2Version = "Unknown";
        double pingMs = -1;
        boolean connected = false;
        java.util.Map<String, Integer> tables = new java.util.LinkedHashMap<>();
        java.util.List<String> existingIndexes = new java.util.ArrayList<>();
        int orphanCount = 0;

        boolean isTimeout = false;
        String diagErr = null;
        long start = System.nanoTime();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery("SELECT 1")) {
                if (rs.next()) connected = true;
            }
            pingMs = Math.round(((System.nanoTime() - start) / 1_000_000.0) * 10.0) / 10.0;

            try (ResultSet rs = stmt.executeQuery("SELECT H2VERSION()")) {
                if (rs.next()) h2Version = rs.getString(1);
            }

            // Table counts
            String[] tableNames = {"PACKAGES", "TYPES", "METHODS", "FIELDS", "RELATIONSHIPS", "INCONSISTENCIES", "SCAN_META", "GIT_COMMITS", "ANALYST_NOTES"};
            for (String tbl : tableNames) {
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tbl)) {
                    if (rs.next()) tables.put(tbl.toLowerCase(), rs.getInt(1));
                } catch (Exception e) {
                    tables.put(tbl.toLowerCase(), -1);
                    if (e instanceof SQLException && isCancelOrTimeoutException((SQLException) e)) {
                        isTimeout = true;
                    }
                }
            }

            // Index checks
            try (ResultSet rs = stmt.executeQuery("SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_SCHEMA = 'PUBLIC'")) {
                while (rs.next()) {
                    existingIndexes.add(rs.getString(1).toLowerCase());
                }
            } catch (Exception e) {
                if (e instanceof SQLException && isCancelOrTimeoutException((SQLException) e)) {
                    isTimeout = true;
                }
            }

            // Orphan count
            try (ResultSet rs = stmt.executeQuery(
                "SELECT (" +
                "  (SELECT COUNT(*) FROM relationships WHERE kind IN ('CALLS','OVERRIDES') AND from_entity_fqn NOT IN (SELECT fqn FROM methods) AND from_entity_fqn NOT IN (SELECT fqn FROM types)) + " +
                "  (SELECT COUNT(*) FROM methods WHERE declaring_type_fqn NOT IN (SELECT fqn FROM types))" +
                ") AS total_orphans")) {
                if (rs.next()) orphanCount = rs.getInt(1);
            } catch (Exception e) {
                if (e instanceof SQLException && isCancelOrTimeoutException((SQLException) e)) {
                    isTimeout = true;
                }
            }

        } catch (Exception e) {
            log.warn("Diagnostics error: {}", e.getMessage());
            diagErr = e.getMessage();
            if (e instanceof SQLException && isCancelOrTimeoutException((SQLException) e)) {
                isTimeout = true;
            }
        }

        diag.put("engine", "H2 " + h2Version);
        diag.put("connected", connected);
        diag.put("pingMs", pingMs);
        diag.put("tables", tables);
        diag.put("orphanCount", orphanCount);
        diag.put("sqlState57014", isTimeout);

        // Critical secondary indexes expected
        java.util.List<String> expectedIndexes = java.util.List.of(
            "idx_types_pkg", "idx_types_kind", "idx_types_pkg_kind", "idx_fields_type",
            "idx_methods_type", "idx_methods_name", "idx_rels_from", "idx_rels_to",
            "idx_rels_kind", "idx_rels_calls_covering", "idx_rels_fields_covering", "idx_pkgs_parent"
        );
        java.util.List<String> missingIndexes = new java.util.ArrayList<>();
        for (String exp : expectedIndexes) {
            if (!existingIndexes.contains(exp)) {
                missingIndexes.add(exp);
            }
        }

        java.util.Map<String, Object> indexInfo = new java.util.LinkedHashMap<>();
        indexInfo.put("total", existingIndexes.size());
        indexInfo.put("verified", missingIndexes.isEmpty());
        indexInfo.put("missing", missingIndexes);
        diag.put("indexes", indexInfo);

        // Overall health status
        String status;
        String message;
        if (!connected) {
            if (isTimeout) {
                status = "LOCKED";
                message = "SQLState 57014 (Statement Timeout / Lock Contention) detected. Click 'Restart Database' to reset connection pool and release locks.";
            } else {
                status = "CORRUPTED";
                message = "Database connection failed: " + (diagErr != null ? diagErr : "database file is locked or corrupted.");
            }
        } else if (isTimeout) {
            status = "DEGRADED";
            message = "SQLState 57014 (Statement Timeout / Lock Contention) detected during diagnostics. Click 'Restart Database' to reset pool and clear locks.";
        } else if (!missingIndexes.isEmpty()) {
            status = "DEGRADED";
            message = "Missing " + missingIndexes.size() + " secondary index(es). Click 'Self-Heal Indexes' to rebuild.";
        } else if (orphanCount > 0) {
            status = "DEGRADED";
            message = "Found " + orphanCount + " orphan records. Click 'Purge Orphans' to clean.";
        } else {
            status = "HEALTHY";
            message = "Database is healthy. All tables and secondary indexes intact (" + pingMs + "ms ping).";
        }
        diag.put("status", status);
        diag.put("statusMessage", message);
        diag.put("lastChecked", System.currentTimeMillis());

        java.util.Map<String, Object> leakStats = new java.util.LinkedHashMap<>();
        leakStats.put("enabled", true);
        leakStats.put("thresholdMs", leakThresholdMs);
        leakStats.put("totalDetected", totalLeaksDetected.get());
        leakStats.put("totalRecovered", totalLeaksRecovered.get());
        leakStats.put("activeTracked", activeLeases.size());
        leakStats.put("lastRecovered", lastRecoveredLeak);
        diag.put("leakRecovery", leakStats);

        return diag;
    }

    /** Runs an interactive health check and returns complete diagnostic results. */
    public java.util.Map<String, Object> runHealthCheck() {
        return getDiagnostics();
    }

    /** Purges dangling relationships and orphaned records from deleted/invalid source entities. */
    public int purgeOrphanData() {
        log.info("Purging orphan data from H2 database...");
        int purged = 0;
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            try {
                int relsFrom = stmt.executeUpdate(
                    "DELETE FROM relationships WHERE kind IN ('CALLS', 'OVERRIDES') " +
                    "AND from_entity_fqn NOT IN (SELECT fqn FROM methods) " +
                    "AND from_entity_fqn NOT IN (SELECT fqn FROM types)"
                );
                int relsTo = stmt.executeUpdate(
                    "DELETE FROM relationships WHERE kind = 'CALLS' " +
                    "AND to_entity_fqn NOT IN (SELECT fqn FROM methods)"
                );
                int relsFields = stmt.executeUpdate(
                    "DELETE FROM relationships WHERE kind IN ('READS_FIELD', 'WRITES_FIELD') " +
                    "AND to_entity_fqn NOT IN (SELECT fqn FROM fields)"
                );
                int orphanMethods = stmt.executeUpdate(
                    "DELETE FROM methods WHERE declaring_type_fqn NOT IN (SELECT fqn FROM types)"
                );
                int orphanFields = stmt.executeUpdate(
                    "DELETE FROM fields WHERE declaring_type_fqn NOT IN (SELECT fqn FROM types)"
                );
                conn.commit();
                purged = relsFrom + relsTo + relsFields + orphanMethods + orphanFields;
                log.info("Orphan purge complete: {} records removed", purged);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.error("Failed to purge orphan data: {}", e.getMessage(), e);
        }
        return purged;
    }


    // ─────────────────────────────────────────────────────────────────────────
    // DDL – idempotent CREATE IF NOT EXISTS for every table and index
    // ─────────────────────────────────────────────────────────────────────────

    private void createSchema() throws SQLException {
        try (Connection conn = getConnection();
             Statement  stmt = conn.createStatement()) {

            // packages ──────────────────────────────────────────────────────
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS packages (" +
                "  id         VARCHAR PRIMARY KEY," +
                "  fqn        VARCHAR NOT NULL," +
                "  name       VARCHAR NOT NULL," +
                "  parent_fqn VARCHAR," +
                "  file_count INTEGER DEFAULT 0," +
                "  type_count INTEGER DEFAULT 0" +
                ")");

            // types ─────────────────────────────────────────────────────────
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS types (" +
                "  id           VARCHAR PRIMARY KEY," +
                "  fqn          VARCHAR NOT NULL," +
                "  simple_name  VARCHAR NOT NULL," +
                "  package_fqn  VARCHAR," +
                "  kind         VARCHAR NOT NULL," +
                "  modifiers    VARCHAR," +
                "  super_class  VARCHAR," +
                "  interfaces   CLOB," +     // JSON array of strings
                "  source_file  VARCHAR," +
                "  start_line   INTEGER," +
                "  end_line     INTEGER," +
                "  line_count   INTEGER DEFAULT 0," +
                "  field_count  INTEGER DEFAULT 0," +
                "  method_count INTEGER DEFAULT 0" +
                ")");

            // fields ────────────────────────────────────────────────────────
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS fields (" +
                "  id                 VARCHAR PRIMARY KEY," +
                "  fqn                VARCHAR NOT NULL," +
                "  simple_name        VARCHAR NOT NULL," +
                "  declaring_type_fqn VARCHAR NOT NULL," +
                "  field_type         VARCHAR," +
                "  modifiers          VARCHAR," +
                "  initializer        VARCHAR," +
                "  start_line         INTEGER" +
                ")");

            // methods ───────────────────────────────────────────────────────
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS methods (" +
                "  id                    VARCHAR PRIMARY KEY," +
                "  fqn                   VARCHAR NOT NULL," +
                "  simple_name           VARCHAR NOT NULL," +
                "  declaring_type_fqn    VARCHAR NOT NULL," +
                "  return_type           VARCHAR," +
                "  parameters            CLOB," +   // JSON array of {type,name}
                "  modifiers             VARCHAR," +
                "  start_line            INTEGER," +
                "  end_line              INTEGER," +
                "  cyclomatic_complexity INTEGER DEFAULT 1," +
                "  body_hash             VARCHAR" +
                ")");

            // relationships ─────────────────────────────────────────────────
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS relationships (" +
                "  id               VARCHAR PRIMARY KEY," +
                "  from_entity_fqn  VARCHAR NOT NULL," +
                "  to_entity_fqn    VARCHAR NOT NULL," +
                "  kind             VARCHAR NOT NULL," +
                "  source_line      INTEGER" +
                ")");

            // analyst_notes ─────────────────────────────────────────────────
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS analyst_notes (" +
                "  id         VARCHAR PRIMARY KEY," +
                "  entity_fqn VARCHAR NOT NULL," +
                "  content    CLOB," +
                "  created_at BIGINT," +
                "  updated_at BIGINT" +
                ")");

            // inconsistencies ───────────────────────────────────────────────
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS inconsistencies (" +
                "  id               VARCHAR PRIMARY KEY," +
                "  entity1_fqn      VARCHAR NOT NULL," +
                "  entity1_kind     VARCHAR NOT NULL," +
                "  entity2_fqn      VARCHAR NOT NULL," +
                "  entity2_kind     VARCHAR NOT NULL," +
                "  reason           VARCHAR," +
                "  similarity_score DOUBLE," +
                "  kind             VARCHAR" +
                ")");

            // scan_meta ─────────────────────────────────────────────────────
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS scan_meta (" +
                "  id                  VARCHAR PRIMARY KEY," +
                "  status              VARCHAR NOT NULL," +
                "  source_path         VARCHAR NOT NULL," +
                "  total_files         INTEGER DEFAULT 0," +
                "  processed_files     INTEGER DEFAULT 0," +
                "  parsed_files        INTEGER DEFAULT 0," +
                "  error_files         INTEGER DEFAULT 0," +
                "  types_found         INTEGER DEFAULT 0," +
                "  methods_found       INTEGER DEFAULT 0," +
                "  fields_found        INTEGER DEFAULT 0," +
                "  relationships_found INTEGER DEFAULT 0," +
                "  start_time          BIGINT DEFAULT 0," +
                "  end_time            BIGINT DEFAULT 0," +
                "  message             VARCHAR," +
                "  error_detail        VARCHAR" +
                ")");

            // git_meta ──────────────────────────────────────────────────────
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS git_meta (" +
                "  entity_fqn        VARCHAR PRIMARY KEY," +
                "  last_author_name  VARCHAR," +
                "  last_author_email VARCHAR," +
                "  last_commit_time  BIGINT DEFAULT 0," +
                "  last_commit_hash  VARCHAR," +
                "  last_commit_msg   VARCHAR," +
                "  commit_count      INTEGER DEFAULT 0" +
                ")");


            // file_meta ─────────────────────────────────────────────────────
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS file_meta (" +
                "  file_path     VARCHAR PRIMARY KEY," +
                "  last_modified BIGINT NOT NULL," +
                "  file_size     BIGINT NOT NULL," +
                "  type_count    INTEGER DEFAULT 0" +
                ")");

            // excluded_scopes ───────────────────────────────────────────────
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS excluded_scopes (" +
                "  id          VARCHAR PRIMARY KEY," +
                "  entity_type VARCHAR NOT NULL," +
                "  fqn         VARCHAR NOT NULL UNIQUE," +
                "  simple_name VARCHAR NOT NULL," +
                "  source_file VARCHAR," +
                "  excluded_at BIGINT DEFAULT 0" +
                ")");

            // Indices for fast lookups ───────────────────────────────────────
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_types_pkg      ON types(package_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_types_src      ON types(source_file)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_types_kind     ON types(kind)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_types_pkg_kind ON types(package_fqn, kind)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_fields_type    ON fields(declaring_type_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_methods_type   ON methods(declaring_type_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_methods_name   ON methods(simple_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rels_from      ON relationships(from_entity_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rels_to        ON relationships(to_entity_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rels_kind      ON relationships(kind)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rels_calls_covering ON relationships(kind, id, from_entity_fqn, to_entity_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rels_fields_covering ON relationships(kind, id, to_entity_fqn, from_entity_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pkgs_parent    ON packages(parent_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_notes_ent      ON analyst_notes(entity_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_excluded_fqn   ON excluded_scopes(fqn)");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bulk load and maintenance optimizations
    // ─────────────────────────────────────────────────────────────────────────

    /** Instant truncate of all scan data without generating MVCC dead page bloat. */
    public void clearAll() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE inconsistencies");
            stmt.execute("TRUNCATE TABLE git_meta");
            stmt.execute("TRUNCATE TABLE file_meta");
            stmt.execute("TRUNCATE TABLE relationships");
            stmt.execute("TRUNCATE TABLE methods");
            stmt.execute("TRUNCATE TABLE fields");
            stmt.execute("TRUNCATE TABLE types");
            stmt.execute("TRUNCATE TABLE packages");
            log.info("All scan tables truncated cleanly");
        }
    }

    /** Prepares H2 for high-throughput streaming inserts (drops secondary indexes, disables undo log). */
    public void prepareForBulkLoad() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET WRITE_DELAY 2000");
            stmt.execute("DROP INDEX IF EXISTS idx_types_pkg");
            stmt.execute("DROP INDEX IF EXISTS idx_types_src");
            stmt.execute("DROP INDEX IF EXISTS idx_types_kind");
            stmt.execute("DROP INDEX IF EXISTS idx_types_pkg_kind");
            stmt.execute("DROP INDEX IF EXISTS idx_fields_type");
            stmt.execute("DROP INDEX IF EXISTS idx_methods_type");
            stmt.execute("DROP INDEX IF EXISTS idx_rels_from");
            stmt.execute("DROP INDEX IF EXISTS idx_rels_to");
            stmt.execute("DROP INDEX IF EXISTS idx_rels_kind");
            stmt.execute("DROP INDEX IF EXISTS idx_rels_calls_covering");
            stmt.execute("DROP INDEX IF EXISTS idx_rels_fields_covering");
            stmt.execute("DROP INDEX IF EXISTS idx_pkgs_parent");
            log.info("H2 configured for high-speed bulk ingestion (secondary indexes dropped, write delay 2000ms)");
        }
    }

    @FunctionalInterface
    public interface IndexProgressListener {
        void onIndexProgress(int currentStep, int totalSteps, String indexName, String tableName, String description);
    }

    private static class IndexTask {
        final String sql;
        final String indexName;
        final String tableName;
        final String description;
        IndexTask(String sql, String indexName, String tableName, String description) {
            this.sql = sql;
            this.indexName = indexName;
            this.tableName = tableName;
            this.description = description;
        }
    }

    /** Rebuilds secondary indexes and runs query analyzer after bulk ingestion finishes. */
    public void finishBulkLoad() throws SQLException {
        finishBulkLoad(null);
    }

    /** Rebuilds secondary indexes and runs query analyzer with live progress reporting. */
    public void finishBulkLoad(IndexProgressListener listener) throws SQLException {
        IndexTask[] tasks = {
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_types_pkg      ON types(package_fqn)",
                          "idx_types_pkg", "types", "Package lookup index on types(package_fqn)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_types_src      ON types(source_file)",
                          "idx_types_src", "types", "Source file index on types(source_file)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_types_kind     ON types(kind)",
                          "idx_types_kind", "types", "Type kind filter index on types(kind)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_types_pkg_kind ON types(package_fqn, kind)",
                          "idx_types_pkg_kind", "types", "Composite package/kind index on types(package_fqn, kind)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_fields_type    ON fields(declaring_type_fqn)",
                          "idx_fields_type", "fields", "Declaring class index on fields(declaring_type_fqn)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_methods_type   ON methods(declaring_type_fqn)",
                          "idx_methods_type", "methods", "Declaring class index on methods(declaring_type_fqn)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_methods_name   ON methods(simple_name)",
                          "idx_methods_name", "methods", "Method simple name index on methods(simple_name)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_rels_from      ON relationships(from_entity_fqn)",
                          "idx_rels_from", "relationships", "Source caller/reader index on relationships(from_entity_fqn)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_rels_to        ON relationships(to_entity_fqn)",
                          "idx_rels_to", "relationships", "Target callee/field index on relationships(to_entity_fqn)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_rels_kind      ON relationships(kind)",
                          "idx_rels_kind", "relationships", "Relationship kind filter on relationships(kind)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_rels_calls_covering ON relationships(kind, id, from_entity_fqn, to_entity_fqn)",
                          "idx_rels_calls_covering", "relationships", "Covering index for call graph extraction on relationships(kind, id, from_entity_fqn, to_entity_fqn)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_rels_fields_covering ON relationships(kind, id, to_entity_fqn, from_entity_fqn)",
                          "idx_rels_fields_covering", "relationships", "Covering index for field impact analysis on relationships(kind, id, to_entity_fqn, from_entity_fqn)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_pkgs_parent    ON packages(parent_fqn)",
                          "idx_pkgs_parent", "packages", "Package hierarchy tree index on packages(parent_fqn)"),
            new IndexTask("ANALYZE",
                          "ANALYZE", "all tables", "Computing cost-based query optimizer table statistics"),
            new IndexTask("SET WRITE_DELAY 500",
                          "SET WRITE_DELAY", "h2 engine", "Restoring safe transaction commit flush delay"),
            new IndexTask("CHECKPOINT SYNC",
                          "CHECKPOINT SYNC", "h2 engine", "Compacting and defragmenting database file on disk")
        };

        for (int i = 0; i < tasks.length; i++) {
            IndexTask t = tasks[i];
            if (listener != null) {
                listener.onIndexProgress(i + 1, tasks.length, t.indexName, t.tableName, t.description);
            }
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute(t.sql);
            }
        }
        log.info("H2 bulk ingestion finalized (indexes rebuilt and analyzed)");
    }


    /**
     * Checks if the given SQLException represents a statement cancellation, query timeout,
     * or lock timeout (SQLState 57014 / H2 ErrorCode 90051).
     */
    public static boolean isCancelOrTimeoutException(SQLException e) {
        if (e == null) return false;
        String sqlState = e.getSQLState();
        if (sqlState != null && (sqlState.startsWith("57") || "57014".equals(sqlState))) {
            return true;
        }
        int code = e.getErrorCode();
        if (code == 90051 || code == 57014) {
            return true;
        }
        String msg = e.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            return lower.contains("57014") || lower.contains("statement was canceled") || lower.contains("timeout trying to lock table");
        }
        return false;
    }

    /**
     * Evicts a connection from the pool if it encountered SQLState 57014 or another fatal error,
     * preventing poisoned connections from circulating in the pool.
     */
    public void evictIfPoisoned(Connection conn, SQLException e) {
        if (conn != null && dataSource != null && !dataSource.isClosed()) {
            if (isCancelOrTimeoutException(e)) {
                log.warn("SQLState 57014 (statement canceled / lock timeout) detected on connection. Evicting connection from pool...");
                try {
                    conn.rollback();
                } catch (Exception ignored) {}
                try {
                    dataSource.evictConnection(conn);
                } catch (Exception ex) {
                    log.debug("Error evicting connection: {}", ex.getMessage());
                }
            }
        }
    }

    /** Forces H2 MVStore compaction to rewrite file without dead page fragments. */
    public void compactDatabase() {
        log.info("Compacting H2 database storage...");
        try {
            if (dataSource != null && !dataSource.isClosed()) {
                try (Connection conn = dataSource.getConnection();
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("SHUTDOWN COMPACT");
                } catch (Exception e) {
                    log.debug("SHUTDOWN COMPACT command returned: {}", e.getMessage());
                }
                dataSource.close();
            }
            // Re-open connection pool
            HikariConfig cfg = createHikariConfig();
            dataSource = new HikariDataSource(cfg);
            log.info("H2 database compaction complete; connection pool reconnected");
        } catch (Exception e) {
            log.error("Failed to compact database: {}", e.getMessage(), e);
        }
    }

    /**
     * Forcefully restarts the H2 database engine and connection pool.
     * Clears all lingering sessions, active locks, and poisoned connections (e.g. after SQLState 57014).
     */
    public synchronized java.util.Map<String, Object> restartDatabase() {
        log.warn("Initiating full H2 database and connection pool restart...");
        java.util.Map<String, Object> report = new java.util.LinkedHashMap<>();
        java.util.List<String> actions = new java.util.ArrayList<>();
        long start = System.currentTimeMillis();

        if (dataSource != null && !dataSource.isClosed()) {
            for (ConnectionLease lease : activeLeases.values()) {
                lease.markRecovered();
            }
            activeLeases.clear();

            try {
                if (dataSource.getHikariPoolMXBean() != null) {
                    dataSource.getHikariPoolMXBean().softEvictConnections();
                    actions.add("Soft-evicted existing connection pool");
                }
            } catch (Exception e) {
                log.debug("Soft evict note: {}", e.getMessage());
            }

            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("ROLLBACK");
                actions.add("Rolled back active transactions");
            } catch (Exception e) {
                log.debug("Rollback before restart: {}", e.getMessage());
            }

            try {
                dataSource.close();
                actions.add("Closed Hikari connection pool and flushed H2 file handles");
            } catch (Exception e) {
                log.warn("Error closing data source: {}", e.getMessage());
            }
        }

        // Re-create and open fresh HikariDataSource
        boolean verified = false;
        double pingMs = -1;
        try {
            HikariConfig cfg = createHikariConfig();
            dataSource = new HikariDataSource(cfg);
            actions.add("Initialized fresh HikariCP connection pool (" + cfg.getPoolName() + ")");

            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                long pStart = System.nanoTime();
                try (ResultSet rs = stmt.executeQuery("SELECT 1")) {
                    if (rs.next()) verified = true;
                }
                pingMs = Math.round(((System.nanoTime() - pStart) / 1_000_000.0) * 10.0) / 10.0;
                actions.add("Verified database connectivity with fresh session (" + pingMs + "ms ping)");
            }

            ensureSecondaryIndexes();
            actions.add("Verified and restored secondary indexes");
        } catch (Exception e) {
            log.error("Failed to restart database: {}", e.getMessage(), e);
            report.put("success", false);
            report.put("error", e.getMessage());
            report.put("actions", actions);
            return report;
        }

        long elapsedMs = System.currentTimeMillis() - start;
        report.put("success", verified);
        report.put("pingMs", pingMs);
        report.put("elapsedMs", elapsedMs);
        report.put("actions", actions);
        report.put("message", verified ? "Database and connection pool successfully restarted in " + elapsedMs + "ms (cleared locks & SQLState 57014)." : "Database restart completed with errors.");
        return report;
    }

    /**
     * Scans active connection leases, detects leaks (held > leakThresholdMs or borrowing thread died),
     * and automatically recovers them by rolling back locks and evicting from HikariCP.
     */
    public synchronized int checkAndRecoverConnectionLeaks() {
        if (dataSource == null || dataSource.isClosed()) return 0;

        int recoveredCount = 0;

        // 1. Check tracked connections
        for (java.util.Map.Entry<Connection, ConnectionLease> entry : activeLeases.entrySet()) {
            Connection rawConn = entry.getKey();
            ConnectionLease lease = entry.getValue();

            if (lease.isClosed() || lease.isRecovered()) {
                activeLeases.remove(rawConn);
                continue;
            }

            long heldMs = lease.getHoldDurationMs();
            long idleMs = lease.getIdleDurationMs();

            boolean isDeadThread = !isThreadAlive(lease.threadId);
            boolean isTimedOut = heldMs >= leakThresholdMs;

            if (isDeadThread || isTimedOut) {
                String reason = isDeadThread
                    ? "Borrowing thread terminated without closing connection (" + (heldMs / 1000) + "s held)"
                    : "Connection hold timeout exceeded (" + (heldMs / 1000) + "s held, " + (idleMs / 1000) + "s idle)";

                log.warn("[LEAK-RECOVERY] Connection leak detected! {}. Allocation site: {}\nAuto-recovering and evicting connection...",
                         reason, lease.getAllocationSite());

                if (autoRecoverLease(lease, reason)) {
                    recoveredCount++;
                }
            }
        }

        // 2. Pool Starvation Check: if threads are awaiting connection and pool is saturated
        com.zaxxer.hikari.HikariPoolMXBean mx = dataSource.getHikariPoolMXBean();
        if (mx != null && mx.getThreadsAwaitingConnection() > 0 && mx.getActiveConnections() >= dataSource.getMaximumPoolSize()) {
            log.warn("[POOL-STARVATION] Pool saturated (active={}/{}, awaiting={}). Checking for stuck connections to reclaim...",
                     mx.getActiveConnections(), dataSource.getMaximumPoolSize(), mx.getThreadsAwaitingConnection());

            ConnectionLease oldestCandidate = null;
            for (ConnectionLease lease : activeLeases.values()) {
                if (!lease.isClosed() && !lease.isRecovered() && lease.getIdleDurationMs() >= 15_000) {
                    if (oldestCandidate == null || lease.getIdleDurationMs() > oldestCandidate.getIdleDurationMs()) {
                        oldestCandidate = lease;
                    }
                }
            }

            if (oldestCandidate != null) {
                String reason = "Pool starvation emergency reclamation (" + (oldestCandidate.getIdleDurationMs() / 1000) + "s idle with "
                    + mx.getThreadsAwaitingConnection() + " threads waiting)";
                log.warn("[POOL-STARVATION-RECOVERY] Auto-recovering stuck connection held by thread '{}'.", oldestCandidate.threadName);
                if (autoRecoverLease(oldestCandidate, reason)) {
                    recoveredCount++;
                }
            }
        }

        return recoveredCount;
    }

    private boolean isThreadAlive(long threadId) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getId() == threadId) {
                return t.isAlive();
            }
        }
        return false;
    }

    private boolean autoRecoverLease(ConnectionLease lease, String reason) {
        if (lease.markRecovered()) {
            activeLeases.remove(lease.rawConnection);
            totalLeaksDetected.incrementAndGet();

            try {
                if (!lease.rawConnection.isClosed() && !lease.rawConnection.getAutoCommit()) {
                    lease.rawConnection.rollback();
                }
            } catch (Exception ignored) {}

            try {
                if (dataSource != null && !dataSource.isClosed()) {
                    dataSource.evictConnection(lease.rawConnection);
                } else {
                    lease.rawConnection.close();
                }
            } catch (Exception e) {
                log.warn("Error evicting leaked connection: {}", e.getMessage());
            }

            totalLeaksRecovered.incrementAndGet();

            java.util.Map<String, Object> leakInfo = new java.util.LinkedHashMap<>();
            leakInfo.put("threadName", lease.threadName);
            leakInfo.put("threadId", lease.threadId);
            leakInfo.put("durationMs", lease.getHoldDurationMs());
            leakInfo.put("idleMs", lease.getIdleDurationMs());
            leakInfo.put("reason", reason);
            leakInfo.put("allocationSite", lease.getAllocationSite());
            leakInfo.put("recoveredAt", System.currentTimeMillis());
            lastRecoveredLeak = leakInfo;

            log.info("[LEAK-RECOVERY] Successfully auto-recovered and evicted leaked connection ({}) in {}ms.",
                     reason, lease.getHoldDurationMs());
            return true;
        }
        return false;
    }

    /** Manually triggers an immediate sweep of active leases to detect and reclaim any leaked connections. */
    public java.util.Map<String, Object> sweepConnectionLeaks() {
        int evicted = checkAndRecoverConnectionLeaks();
        java.util.Map<String, Object> res = new java.util.LinkedHashMap<>();
        res.put("success", true);
        res.put("evictedCount", evicted);
        res.put("activeTracked", activeLeases.size());
        res.put("totalLeaksDetected", totalLeaksDetected.get());
        res.put("totalLeaksRecovered", totalLeaksRecovered.get());
        res.put("lastRecovered", lastRecoveredLeak);
        res.put("message", evicted > 0
            ? "Auto-recovered and evicted " + evicted + " leaked connection(s) from pool."
            : "No connection leaks detected. All " + activeLeases.size() + " active connection(s) are healthy.");
        return res;
    }

    public int getActiveLeaseCount() {
        return activeLeases.size();
    }

    public int getRecoveredLeakCount() {
        return totalLeaksRecovered.get();
    }

    public static class ConnectionLease {
        final Connection rawConnection;
        final long borrowedAt;
        final long threadId;
        final String threadName;
        final StackTraceElement[] allocationStack;
        volatile long lastActivityAt;
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean recovered = new java.util.concurrent.atomic.AtomicBoolean(false);

        public ConnectionLease(Connection rawConnection) {
            this.rawConnection = rawConnection;
            this.borrowedAt = System.currentTimeMillis();
            this.lastActivityAt = this.borrowedAt;
            Thread t = Thread.currentThread();
            this.threadId = t.getId();
            this.threadName = t.getName();
            this.allocationStack = t.getStackTrace();
        }

        public void touch() {
            this.lastActivityAt = System.currentTimeMillis();
        }

        public boolean markClosed() {
            return closed.compareAndSet(false, true);
        }

        public boolean markRecovered() {
            boolean r = recovered.compareAndSet(false, true);
            if (r) {
                closed.set(true);
            }
            return r;
        }

        public boolean isClosed() {
            return closed.get();
        }

        public boolean isRecovered() {
            return recovered.get();
        }

        public long getHoldDurationMs() {
            return System.currentTimeMillis() - borrowedAt;
        }

        public long getIdleDurationMs() {
            return System.currentTimeMillis() - lastActivityAt;
        }

        public String getAllocationSite() {
            if (allocationStack == null) return "Unknown";
            for (StackTraceElement elem : allocationStack) {
                String cls = elem.getClassName();
                if (!cls.startsWith("com.codelens.storage.DatabaseManager") &&
                    !cls.startsWith("java.lang.") &&
                    !cls.startsWith("jdk.internal.") &&
                    !cls.startsWith("com.zaxxer.hikari")) {
                    return elem.getClassName() + "." + elem.getMethodName() + "(" + elem.getFileName() + ":" + elem.getLineNumber() + ")";
                }
            }
            return allocationStack.length > 0 ? allocationStack[0].toString() : "Unknown";
        }
    }

    private static class TrackedConnectionHandler implements java.lang.reflect.InvocationHandler {
        private final Connection rawConn;
        private final ConnectionLease lease;
        private final DatabaseManager dbManager;

        public TrackedConnectionHandler(Connection rawConn, ConnectionLease lease, DatabaseManager dbManager) {
            this.rawConn = rawConn;
            this.lease = lease;
            this.dbManager = dbManager;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            if ("close".equals(methodName)) {
                if (lease.markClosed()) {
                    dbManager.unregisterLease(rawConn);
                    rawConn.close();
                }
                return null;
            }

            if ("isClosed".equals(methodName)) {
                return lease.isClosed() || lease.isRecovered() || rawConn.isClosed();
            }

            if (lease.isRecovered()) {
                throw new SQLException("Connection was auto-recovered and evicted by CodeLens DatabaseManager due to a detected connection leak (held "
                    + lease.getHoldDurationMs() + "ms without closure at " + lease.getAllocationSite() + ")");
            }

            if ("createStatement".equals(methodName) ||
                "prepareStatement".equals(methodName) ||
                "prepareCall".equals(methodName) ||
                "commit".equals(methodName) ||
                "rollback".equals(methodName) ||
                "setAutoCommit".equals(methodName) ||
                "setSavepoint".equals(methodName) ||
                "releaseSavepoint".equals(methodName)) {
                lease.touch();
            }

            if ("unwrap".equals(methodName)) {
                Class<?> iface = (Class<?>) args[0];
                if (iface.isInstance(proxy)) return proxy;
                if (iface.isInstance(rawConn)) return rawConn;
                return rawConn.unwrap(iface);
            }

            if ("isWrapperFor".equals(methodName)) {
                Class<?> iface = (Class<?>) args[0];
                if (iface.isInstance(proxy) || iface.isInstance(rawConn)) return true;
                return rawConn.isWrapperFor(iface);
            }

            if ("equals".equals(methodName)) {
                Object other = args[0];
                return proxy == other || rawConn.equals(other);
            }

            if ("hashCode".equals(methodName)) {
                return System.identityHashCode(proxy);
            }

            if ("toString".equals(methodName)) {
                return "TrackedConnection[" + rawConn.toString() + ", held " + lease.getHoldDurationMs() + "ms, site=" + lease.getAllocationSite() + "]";
            }

            try {
                return method.invoke(rawConn, args);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable target = ite.getTargetException();
                if (target instanceof SQLException) {
                    if (dbManager.isCancelOrTimeoutException((SQLException) target)) {
                        dbManager.evictIfPoisoned(rawConn, (SQLException) target);
                    }
                    throw (SQLException) target;
                }
                if (target instanceof RuntimeException) throw (RuntimeException) target;
                if (target instanceof Error) throw (Error) target;
                throw new SQLException(target);
            }
        }
    }
}

