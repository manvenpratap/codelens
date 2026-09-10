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

    public DatabaseManager(String dataDir) {
        this.dataDir = dataDir;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    private HikariConfig createHikariConfig() {
        HikariConfig cfg = new HikariConfig();
        // DB_CLOSE_DELAY=-1: keep H2 alive as long as the JVM runs.
        // CACHE_SIZE=524288 (512MB cache), PAGE_SIZE=8192 for high IOPS on large repos.
        // COMPRESS=FALSE: disable page-level compression to eliminate CPU serialization during bulk ingestion.
        // AUTO_COMPACT_FILL_RATE=0: disable background page compaction during active ingestion.
        // RETENTION_TIME=0: immediately release old transaction page versions in MVStore (avoids version bloat on 50k+ files).
        // LOCK_TIMEOUT=30000: 30s timeout to handle heavy I/O gracefully without premature lock aborts.
        cfg.setJdbcUrl("jdbc:h2:file:" + dataDir + "/codelens_db"
                     + ";AUTO_SERVER=FALSE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000;CACHE_SIZE=524288;PAGE_SIZE=8192;DEFRAG_ALWAYS=FALSE;COMPRESS=FALSE;AUTO_COMPACT_FILL_RATE=0;RETENTION_TIME=0");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(20);
        cfg.setMinimumIdle(4);
        cfg.setConnectionTimeout(60_000);
        cfg.setValidationTimeout(5_000);
        cfg.setMaxLifetime(1800_000);
        cfg.setLeakDetectionThreshold(600_000); // 10 minutes - avoids false leak alarms during large repo index builds
        cfg.setPoolName("CodeLens-H2");
        return cfg;
    }

    /** Initialises the connection pool and creates all tables. */
    public void initialize() throws Exception {
        Files.createDirectories(Paths.get(dataDir));
        HikariConfig cfg = createHikariConfig();
        dataSource = new HikariDataSource(cfg);

        createSchema();
        ensureSecondaryIndexes();
        log.info("H2 database initialised at {}/codelens_db (indexes verified)", dataDir);
    }

    /** Self-healing check: verify secondary indexes exist; rebuild if dropped by a previous crash during bulk load. */
    private void ensureSecondaryIndexes() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'METHODS' AND INDEX_NAME = 'IDX_METHODS_TYPE'")) {
            if (rs.next() && rs.getInt(1) == 0) {
                log.warn("Secondary index idx_methods_type missing (prior crash during bulk load). Self-healing indexes now...");
                finishBulkLoad();
            }
        } catch (Exception e) {
            log.warn("Could not verify secondary indexes: {}", e.getMessage());
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }

    /** Expose a connection from the pool (caller must close it). */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
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

            // Indices for fast lookups ───────────────────────────────────────
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_types_pkg      ON types(package_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_types_src      ON types(source_file)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_types_kind     ON types(kind)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_types_pkg_kind ON types(package_fqn, kind)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_fields_type    ON fields(declaring_type_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_methods_type   ON methods(declaring_type_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rels_from      ON relationships(from_entity_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rels_to        ON relationships(to_entity_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rels_kind      ON relationships(kind)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pkgs_parent    ON packages(parent_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_notes_ent      ON analyst_notes(entity_fqn)");
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
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_rels_from      ON relationships(from_entity_fqn)",
                          "idx_rels_from", "relationships", "Source caller/reader index on relationships(from_entity_fqn)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_rels_to        ON relationships(to_entity_fqn)",
                          "idx_rels_to", "relationships", "Target callee/field index on relationships(to_entity_fqn)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_rels_kind      ON relationships(kind)",
                          "idx_rels_kind", "relationships", "Relationship kind filter on relationships(kind)"),
            new IndexTask("CREATE INDEX IF NOT EXISTS idx_pkgs_parent    ON packages(parent_fqn)",
                          "idx_pkgs_parent", "packages", "Package hierarchy tree index on packages(parent_fqn)"),
            new IndexTask("ANALYZE",
                          "ANALYZE", "all tables", "Computing cost-based query optimizer table statistics"),
            new IndexTask("SET WRITE_DELAY 500",
                          "SET WRITE_DELAY", "h2 engine", "Restoring safe transaction commit flush delay")
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
}

