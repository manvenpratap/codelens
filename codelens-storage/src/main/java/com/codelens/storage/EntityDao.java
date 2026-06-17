package com.codelens.storage;

import com.codelens.core.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * Single consolidated DAO for all entity types (packages, types, fields,
 * methods, relationships, notes, inconsistencies).
 *
 * Design choice: one DAO class rather than six prevents the boilerplate
 * explosion that often comes with per-entity DAOs in small projects.
 * Each entity group has its own clearly-labelled section.
 *
 * Batch inserts are used for the scan path to keep write throughput high.
 */
public class EntityDao {

    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(EntityDao.class);

    private final DatabaseManager db;
    private final ObjectMapper    json = new ObjectMapper();

    public EntityDao(DatabaseManager db) {
        this.db = db;
    }

    // =========================================================================
    // PACKAGES
    // =========================================================================

    public void batchInsertPackages(List<CodePackage> packages) throws SQLException {
        if (packages.isEmpty()) return;
        String sql = "MERGE INTO packages (id, fqn, name, parent_fqn, file_count, type_count)" +
                     " KEY(id) VALUES (?,?,?,?,?,?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (CodePackage p : packages) {
                ps.setString(1, p.getId());
                ps.setString(2, p.getFqn());
                ps.setString(3, p.getName());
                ps.setString(4, p.getParentFqn());
                ps.setInt(5, p.getFileCount());
                ps.setInt(6, p.getTypeCount());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        }
    }

    public List<CodePackage> findAllPackages() throws SQLException {
        List<CodePackage> list = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM packages ORDER BY fqn");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(pkgFromRs(rs));
        }
        return list;
    }

    // =========================================================================
    // TYPES
    // =========================================================================

    public void batchInsertTypes(List<CodeType> types) throws SQLException {
        if (types.isEmpty()) return;
        String sql =
            "MERGE INTO types " +
            "(id,fqn,simple_name,package_fqn,kind,modifiers,super_class,interfaces," +
            " source_file,start_line,end_line,line_count,field_count,method_count) " +
            "KEY(id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (CodeType t : types) {
                ps.setString(1,  t.getId());
                ps.setString(2,  t.getFqn());
                ps.setString(3,  t.getSimpleName());
                ps.setString(4,  t.getPackageFqn());
                ps.setString(5,  t.getKind());
                ps.setString(6,  t.getModifiers());
                ps.setString(7,  t.getSuperClass());
                ps.setString(8,  toJson(t.getInterfaces()));
                ps.setString(9,  t.getSourceFile());
                ps.setInt(10,    t.getStartLine());
                ps.setInt(11,    t.getEndLine());
                ps.setInt(12,    t.getLineCount());
                ps.setInt(13,    t.getFieldCount());
                ps.setInt(14,    t.getMethodCount());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        }
    }

    public List<CodeType> findAllTypes() throws SQLException {
        return queryTypes("SELECT * FROM types ORDER BY fqn");
    }

    public List<CodeType> findTypesByPackage(String packageFqn) throws SQLException {
        return queryTypesParam("SELECT * FROM types WHERE package_fqn=? ORDER BY simple_name",
                               packageFqn);
    }

    public Optional<CodeType> findTypeById(String id) throws SQLException {
        List<CodeType> result = queryTypesParam(
            "SELECT * FROM types WHERE id=?", id);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    public List<CodeType> searchTypes(String query) throws SQLException {
        String like = "%" + query.toLowerCase() + "%";
        return queryTypesParam(
            "SELECT * FROM types WHERE LOWER(fqn) LIKE ? OR LOWER(simple_name) LIKE ? " +
            "ORDER BY simple_name LIMIT 50",
            like, like);
    }

    // =========================================================================
    // FIELDS
    // =========================================================================

    public void batchInsertFields(List<CodeField> fields) throws SQLException {
        if (fields.isEmpty()) return;
        String sql =
            "MERGE INTO fields " +
            "(id,fqn,simple_name,declaring_type_fqn,field_type,modifiers,initializer,start_line) " +
            "KEY(id) VALUES (?,?,?,?,?,?,?,?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (CodeField f : fields) {
                ps.setString(1, f.getId());
                ps.setString(2, f.getFqn());
                ps.setString(3, f.getSimpleName());
                ps.setString(4, f.getDeclaringTypeFqn());
                ps.setString(5, f.getFieldType());
                ps.setString(6, f.getModifiers());
                ps.setString(7, f.getInitializer());
                ps.setInt(8,    f.getStartLine());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        }
    }

    public List<CodeField> findFieldsByType(String typeFqn) throws SQLException {
        List<CodeField> list = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM fields WHERE declaring_type_fqn=? ORDER BY start_line")) {
            ps.setString(1, typeFqn);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(fieldFromRs(rs));
            }
        }
        return list;
    }

    public Optional<CodeField> findFieldById(String id) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM fields WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(fieldFromRs(rs));
            }
        }
        return Optional.empty();
    }

    // =========================================================================
    // METHODS
    // =========================================================================

    public void batchInsertMethods(List<CodeMethod> methods) throws SQLException {
        if (methods.isEmpty()) return;
        String sql =
            "MERGE INTO methods " +
            "(id,fqn,simple_name,declaring_type_fqn,return_type,parameters,modifiers," +
            " start_line,end_line,cyclomatic_complexity,body_hash) " +
            "KEY(id) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (CodeMethod m : methods) {
                ps.setString(1,  m.getId());
                ps.setString(2,  m.getFqn());
                ps.setString(3,  m.getSimpleName());
                ps.setString(4,  m.getDeclaringTypeFqn());
                ps.setString(5,  m.getReturnType());
                ps.setString(6,  toJson(m.getParameters()));
                ps.setString(7,  m.getModifiers());
                ps.setInt(8,     m.getStartLine());
                ps.setInt(9,     m.getEndLine());
                ps.setInt(10,    m.getCyclomaticComplexity());
                ps.setString(11, m.getBodyHash());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        }
    }

    public List<CodeMethod> findMethodsByType(String typeFqn) throws SQLException {
        List<CodeMethod> list = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM methods WHERE declaring_type_fqn=? ORDER BY start_line")) {
            ps.setString(1, typeFqn);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(methodFromRs(rs));
            }
        }
        return list;
    }

    public Optional<CodeMethod> findMethodById(String id) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM methods WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(methodFromRs(rs));
            }
        }
        return Optional.empty();
    }

    public List<String> findAllMethodFqns() throws SQLException {
        List<String> list = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT fqn FROM methods");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(rs.getString("fqn"));
        }
        return list;
    }

    // =========================================================================
    // RELATIONSHIPS
    // =========================================================================

    public void batchInsertRelationships(List<CodeRelationship> rels) throws SQLException {
        if (rels.isEmpty()) return;
        String sql =
            "MERGE INTO relationships (id, from_entity_fqn, to_entity_fqn, kind, source_line) " +
            "KEY(id) VALUES (?,?,?,?,?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (CodeRelationship r : rels) {
                ps.setString(1, r.getId());
                ps.setString(2, r.getFromEntityFqn());
                ps.setString(3, r.getToEntityFqn());
                ps.setString(4, r.getKind());
                ps.setInt(5,    r.getSourceLine());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        }
    }

    public List<CodeRelationship> findAllRelationships() throws SQLException {
        List<CodeRelationship> list = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM relationships");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(relFromRs(rs));
        }
        return list;
    }

    public List<CodeRelationship> findRelationshipsByKind(String kind) throws SQLException {
        List<CodeRelationship> list = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM relationships WHERE kind=?")) {
            ps.setString(1, kind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(relFromRs(rs));
            }
        }
        return list;
    }

    // =========================================================================
    // ANALYST NOTES
    // =========================================================================

    public void upsertNote(AnalystNote note) throws SQLException {
        String sql =
            "MERGE INTO analyst_notes (id, entity_fqn, content, created_at, updated_at) " +
            "KEY(id) VALUES (?,?,?,?,?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, note.getId());
            ps.setString(2, note.getEntityFqn());
            ps.setString(3, note.getContent());
            ps.setLong(4,   note.getCreatedAt());
            ps.setLong(5,   note.getUpdatedAt());
            ps.executeUpdate();
            c.commit();
        }
    }

    public List<AnalystNote> findNotesByEntity(String entityFqn) throws SQLException {
        List<AnalystNote> list = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM analyst_notes WHERE entity_fqn=? ORDER BY created_at DESC")) {
            ps.setString(1, entityFqn);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AnalystNote n = new AnalystNote();
                    n.setId(rs.getString("id"));
                    n.setEntityFqn(rs.getString("entity_fqn"));
                    n.setContent(rs.getString("content"));
                    n.setCreatedAt(rs.getLong("created_at"));
                    n.setUpdatedAt(rs.getLong("updated_at"));
                    list.add(n);
                }
            }
        }
        return list;
    }

    public boolean deleteNote(String id) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM analyst_notes WHERE id=?")) {
            ps.setString(1, id);
            int rows = ps.executeUpdate();
            c.commit();
            return rows > 0;
        }
    }

    // =========================================================================
    // INCONSISTENCIES
    // =========================================================================

    public void batchInsertInconsistencies(List<InconsistencyReport> reports)
            throws SQLException {
        if (reports.isEmpty()) return;
        // Clear existing before re-inserting (re-scan scenario)
        try (Connection c = db.getConnection();
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM inconsistencies");
            c.commit();
        }
        String sql =
            "INSERT INTO inconsistencies " +
            "(id,entity1_fqn,entity1_kind,entity2_fqn,entity2_kind,reason,similarity_score,kind)" +
            " VALUES (?,?,?,?,?,?,?,?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (InconsistencyReport r : reports) {
                ps.setString(1, r.getId());
                ps.setString(2, r.getEntity1Fqn());
                ps.setString(3, r.getEntity1Kind());
                ps.setString(4, r.getEntity2Fqn());
                ps.setString(5, r.getEntity2Kind());
                ps.setString(6, r.getReason());
                ps.setDouble(7, r.getSimilarityScore());
                ps.setString(8, r.getKind());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        }
    }

    public List<InconsistencyReport> findAllInconsistencies() throws SQLException {
        List<InconsistencyReport> list = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM inconsistencies ORDER BY similarity_score DESC LIMIT 200");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                InconsistencyReport r = new InconsistencyReport();
                r.setId(rs.getString("id"));
                r.setEntity1Fqn(rs.getString("entity1_fqn"));
                r.setEntity1Kind(rs.getString("entity1_kind"));
                r.setEntity2Fqn(rs.getString("entity2_fqn"));
                r.setEntity2Kind(rs.getString("entity2_kind"));
                r.setReason(rs.getString("reason"));
                r.setSimilarityScore(rs.getDouble("similarity_score"));
                r.setKind(rs.getString("kind"));
                list.add(r);
            }
        }
        return list;
    }

    // =========================================================================
    // GIT META
    // =========================================================================

    /**
     * Batch-upsert git metadata records.
     * Uses MERGE so re-scans overwrite stale data cleanly.
     */
    public void batchInsertGitMeta(List<GitMeta> metas) throws SQLException {
        if (metas == null || metas.isEmpty()) return;
        String sql =
            "MERGE INTO git_meta " +
            "(entity_fqn, last_author_name, last_author_email, last_commit_time, " +
            " last_commit_hash, last_commit_msg, commit_count) " +
            "KEY(entity_fqn) VALUES (?,?,?,?,?,?,?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (GitMeta m : metas) {
                ps.setString(1, m.getEntityFqn());
                ps.setString(2, m.getLastAuthorName());
                ps.setString(3, m.getLastAuthorEmail());
                ps.setLong(4,   m.getLastCommitTime());
                ps.setString(5, m.getLastCommitHash());
                ps.setString(6, m.getLastCommitMsg());
                ps.setInt(7,    m.getCommitCount());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        }
    }

    /** Retrieve git metadata for a single entity (returns empty if not present). */
    public Optional<GitMeta> findGitMetaByEntity(String entityFqn) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM git_meta WHERE entity_fqn=?")) {
            ps.setString(1, entityFqn);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(gitMetaFromRs(rs));
            }
        }
        return Optional.empty();
    }

    /** Retrieve all git metadata records (used by the graph heat overlay). */
    public List<GitMeta> findAllGitMeta() throws SQLException {
        List<GitMeta> list = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM git_meta");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(gitMetaFromRs(rs));
        }
        return list;
    }

    /**
     * Returns the top N authors by number of entities they are the last-modifier of,
     * ordered descending. Used for the Git summary panel.
     */
    public List<Map<String, Object>> findTopAuthors(int limit) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        String sql =
            "SELECT last_author_name, last_author_email, COUNT(*) AS entity_count, " +
            "MAX(last_commit_time) AS latest_commit " +
            "FROM git_meta WHERE last_author_name IS NOT NULL " +
            "GROUP BY last_author_name, last_author_email " +
            "ORDER BY entity_count DESC LIMIT ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("authorName",   rs.getString("last_author_name"));
                    row.put("authorEmail",  rs.getString("last_author_email"));
                    row.put("entityCount",  rs.getInt("entity_count"));
                    row.put("latestCommit", rs.getLong("latest_commit"));
                    results.add(row);
                }
            }
        }
        return results;
    }

    /**
     * Returns the top N most-changed entities (highest commit_count),
     * with their last author info. Used for the "hottest files" view.
     */
    public List<Map<String, Object>> findHottestEntities(int limit) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        String sql =
            "SELECT g.entity_fqn, g.commit_count, g.last_author_name, " +
            "g.last_commit_time, g.last_commit_hash " +
            "FROM git_meta g " +
            "WHERE g.commit_count > 0 " +
            "ORDER BY g.commit_count DESC LIMIT ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("entityFqn",     rs.getString("entity_fqn"));
                    row.put("commitCount",   rs.getInt("commit_count"));
                    row.put("lastAuthor",    rs.getString("last_author_name"));
                    row.put("lastCommitTime",rs.getLong("last_commit_time"));
                    row.put("lastCommitHash",rs.getString("last_commit_hash"));
                    results.add(row);
                }
            }
        }
        return results;
    }

    // =========================================================================
    // STATS
    // =========================================================================

    public Map<String, Integer> getStats() throws SQLException {
        Map<String, Integer> stats = new LinkedHashMap<>();
        try (Connection c = db.getConnection();
             Statement s = c.createStatement()) {
            stats.put("packages",       singleInt(s, "SELECT COUNT(*) FROM packages"));
            stats.put("types",          singleInt(s, "SELECT COUNT(*) FROM types"));
            stats.put("fields",         singleInt(s, "SELECT COUNT(*) FROM fields"));
            stats.put("methods",        singleInt(s, "SELECT COUNT(*) FROM methods"));
            stats.put("relationships",  singleInt(s, "SELECT COUNT(*) FROM relationships"));
            stats.put("inconsistencies",singleInt(s, "SELECT COUNT(*) FROM inconsistencies"));
        }
        return stats;
    }

    private int singleInt(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // =========================================================================
    // Row mappers
    // =========================================================================

    private CodePackage pkgFromRs(ResultSet rs) throws SQLException {
        CodePackage p = new CodePackage();
        p.setId(rs.getString("id"));
        p.setFqn(rs.getString("fqn"));
        p.setName(rs.getString("name"));
        p.setParentFqn(rs.getString("parent_fqn"));
        p.setFileCount(rs.getInt("file_count"));
        p.setTypeCount(rs.getInt("type_count"));
        return p;
    }

    private CodeType typeFromRs(ResultSet rs) throws SQLException {
        CodeType t = new CodeType();
        t.setId(rs.getString("id"));
        t.setFqn(rs.getString("fqn"));
        t.setSimpleName(rs.getString("simple_name"));
        t.setPackageFqn(rs.getString("package_fqn"));
        t.setKind(rs.getString("kind"));
        t.setModifiers(rs.getString("modifiers"));
        t.setSuperClass(rs.getString("super_class"));
        t.setSourceFile(rs.getString("source_file"));
        t.setStartLine(rs.getInt("start_line"));
        t.setEndLine(rs.getInt("end_line"));
        t.setLineCount(rs.getInt("line_count"));
        t.setFieldCount(rs.getInt("field_count"));
        t.setMethodCount(rs.getInt("method_count"));
        String ifaces = rs.getString("interfaces");
        if (ifaces != null) {
            try {
                t.setInterfaces(json.readValue(ifaces,
                    new TypeReference<List<String>>() {}));
            } catch (Exception e) { /* leave empty */ }
        }
        return t;
    }

    private CodeField fieldFromRs(ResultSet rs) throws SQLException {
        CodeField f = new CodeField();
        f.setId(rs.getString("id"));
        f.setFqn(rs.getString("fqn"));
        f.setSimpleName(rs.getString("simple_name"));
        f.setDeclaringTypeFqn(rs.getString("declaring_type_fqn"));
        f.setFieldType(rs.getString("field_type"));
        f.setModifiers(rs.getString("modifiers"));
        f.setInitializer(rs.getString("initializer"));
        f.setStartLine(rs.getInt("start_line"));
        return f;
    }

    private CodeMethod methodFromRs(ResultSet rs) throws SQLException {
        CodeMethod m = new CodeMethod();
        m.setId(rs.getString("id"));
        m.setFqn(rs.getString("fqn"));
        m.setSimpleName(rs.getString("simple_name"));
        m.setDeclaringTypeFqn(rs.getString("declaring_type_fqn"));
        m.setReturnType(rs.getString("return_type"));
        m.setModifiers(rs.getString("modifiers"));
        m.setStartLine(rs.getInt("start_line"));
        m.setEndLine(rs.getInt("end_line"));
        m.setCyclomaticComplexity(rs.getInt("cyclomatic_complexity"));
        m.setBodyHash(rs.getString("body_hash"));
        String params = rs.getString("parameters");
        if (params != null) {
            try {
                m.setParameters(json.readValue(params,
                    new TypeReference<List<MethodParam>>() {}));
            } catch (Exception e) { /* leave empty */ }
        }
        return m;
    }

    private CodeRelationship relFromRs(ResultSet rs) throws SQLException {
        CodeRelationship r = new CodeRelationship();
        r.setId(rs.getString("id"));
        r.setFromEntityFqn(rs.getString("from_entity_fqn"));
        r.setToEntityFqn(rs.getString("to_entity_fqn"));
        r.setKind(rs.getString("kind"));
        r.setSourceLine(rs.getInt("source_line"));
        return r;
    }

    private GitMeta gitMetaFromRs(ResultSet rs) throws SQLException {
        GitMeta m = new GitMeta();
        m.setEntityFqn(rs.getString("entity_fqn"));
        m.setLastAuthorName(rs.getString("last_author_name"));
        m.setLastAuthorEmail(rs.getString("last_author_email"));
        m.setLastCommitTime(rs.getLong("last_commit_time"));
        m.setLastCommitHash(rs.getString("last_commit_hash"));
        m.setLastCommitMsg(rs.getString("last_commit_msg"));
        m.setCommitCount(rs.getInt("commit_count"));
        return m;
    }


    // =========================================================================
    // Helpers
    // =========================================================================

    private String toJson(Object obj) {
        try { return json.writeValueAsString(obj); }
        catch (Exception e) { return "[]"; }
    }

    private List<CodeType> queryTypes(String sql) throws SQLException {
        List<CodeType> list = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(typeFromRs(rs));
        }
        return list;
    }

    private List<CodeType> queryTypesParam(String sql, String... params) throws SQLException {
        List<CodeType> list = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(typeFromRs(rs));
            }
        }
        return list;
    }
}
