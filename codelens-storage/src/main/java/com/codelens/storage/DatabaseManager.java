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

    /** Initialises the connection pool and creates all tables. */
    public void initialize() throws Exception {
        Files.createDirectories(Paths.get(dataDir));

        HikariConfig cfg = new HikariConfig();
        // DB_CLOSE_DELAY=-1: keep H2 alive as long as the JVM runs
        cfg.setJdbcUrl("jdbc:h2:file:" + dataDir + "/codelens_db"
                     + ";AUTO_SERVER=FALSE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(10_000);
        cfg.setPoolName("CodeLens-H2");
        dataSource = new HikariDataSource(cfg);

        createSchema();
        log.info("H2 database initialised at {}/codelens_db", dataDir);
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

            // Indices for fast lookups ───────────────────────────────────────
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_types_pkg  ON types(package_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_fields_type ON fields(declaring_type_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_methods_type ON methods(declaring_type_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rels_from  ON relationships(from_entity_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rels_to    ON relationships(to_entity_fqn)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_notes_ent  ON analyst_notes(entity_fqn)");

            conn.commit();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bulk delete — used to clear data before a re-scan
    // ─────────────────────────────────────────────────────────────────────────

    public void clearAll() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM inconsistencies");
            stmt.execute("DELETE FROM git_meta");
            stmt.execute("DELETE FROM relationships");
            stmt.execute("DELETE FROM methods");
            stmt.execute("DELETE FROM fields");
            stmt.execute("DELETE FROM types");
            stmt.execute("DELETE FROM packages");
            conn.commit();
            log.info("All scan data cleared");
        }
    }
}
