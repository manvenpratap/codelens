package com.codelens.storage;

import com.codelens.core.model.*;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Manages the embedded Apache Lucene full-text search index.
 *
 * ADR: Lucene chosen over H2's built-in CONTAINS() for richer tokenisation,
 * prefix/fuzzy matching, and relevance scoring. Index files sit alongside the
 * H2 database in the data directory. All operations are file-based and offline.
 *
 * Indexed entity kinds:  TYPE | METHOD | FIELD
 * Stored fields (returned in hits without a DB round-trip):
 *   id, kind, label, fqn, declaringType
 */
public class LuceneService {

    private static final Logger log = LoggerFactory.getLogger(LuceneService.class);

    // Field names in the Lucene document schema
    private static final String F_ID            = "id";
    private static final String F_KIND          = "kind";       // TYPE | METHOD | FIELD
    private static final String F_LABEL         = "label";      // display name
    private static final String F_FQN           = "fqn";
    private static final String F_SIMPLE_NAME   = "simpleName";
    private static final String F_PACKAGE       = "packageFqn";
    private static final String F_DECLARING     = "declaringType";
    private static final String F_EXTRA         = "extra";      // modifiers, return type, etc.
    private static final String F_SEARCH        = "search";     // all-in-one search field

    private final Path indexDir;
    private FSDirectory     directory;
    private StandardAnalyzer analyzer;
    private IndexWriter      writer;

    public LuceneService(String dataDir) {
        this.indexDir = Paths.get(dataDir, "lucene-index");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /** Opens (or creates) the index directory and initialises the IndexWriter. */
    public void initialize() throws IOException {
        Files.createDirectories(indexDir);
        directory = FSDirectory.open(indexDir);
        analyzer  = new StandardAnalyzer();
        IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        writer    = new IndexWriter(directory, cfg);
        log.info("Lucene index initialised at {}", indexDir);
    }

    /** Flush and close the writer; release OS file handles. */
    public void close() {
        try {
            if (writer != null) writer.close();
            if (directory != null) directory.close();
        } catch (IOException e) {
            log.warn("Error closing Lucene index: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Indexing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Replaces the entire index content with the given entity lists.
     * Called once per scan, after the H2 batch insert succeeds.
     */
    public void rebuildIndex(List<CodeType>   types,
                             List<CodeMethod> methods,
                             List<CodeField>  fields) throws IOException {
        // Wipe and reopen to clear stale entries from a previous scan
        writer.deleteAll();

        for (CodeType t : types) {
            writer.addDocument(buildTypeDoc(t));
        }
        for (CodeMethod m : methods) {
            writer.addDocument(buildMethodDoc(m));
        }
        for (CodeField f : fields) {
            writer.addDocument(buildFieldDoc(f));
        }

        writer.commit();
        log.info("Lucene index rebuilt: {} types, {} methods, {} fields",
            types.size(), methods.size(), fields.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Search
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full-text search across all indexed entities.
     *
     * @param queryStr  raw user query; supports wildcards (foo*) and phrases ("place order")
     * @param maxHits   maximum number of results to return (capped at 100)
     * @return list of lightweight {@link SearchHit} objects
     */
    public List<SearchHit> search(String queryStr, int maxHits) throws Exception {
        if (queryStr == null || queryStr.isBlank()) return Collections.emptyList();
        maxHits = Math.min(maxHits, 100);

        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            // Multi-field parser: simpleName and fqn get highest boost
            Map<String, Float> boosts = new LinkedHashMap<>();
            boosts.put(F_SIMPLE_NAME, 3.0f);
            boosts.put(F_FQN,         2.0f);
            boosts.put(F_SEARCH,      1.0f);

            MultiFieldQueryParser parser = new MultiFieldQueryParser(
                new String[]{F_SIMPLE_NAME, F_FQN, F_SEARCH},
                analyzer, boosts);
            parser.setDefaultOperator(QueryParser.Operator.AND);
            parser.setAllowLeadingWildcard(true);

            // Escape special chars then re-add trailing wildcard for prefix matching
            String escaped = QueryParser.escape(queryStr.trim());
            String qs      = escaped + (escaped.contains("*") ? "" : "*");

            Query q;
            try {
                q = parser.parse(qs);
            } catch (Exception ex) {
                // Fallback to simple term query if parse fails
                q = new WildcardQuery(new Term(F_SEARCH, "*" + escaped + "*"));
            }

            TopDocs hits = searcher.search(q, maxHits);
            List<SearchHit> results = new ArrayList<>(hits.scoreDocs.length);

            for (ScoreDoc sd : hits.scoreDocs) {
                org.apache.lucene.document.Document doc = searcher.doc(sd.doc);
                results.add(new SearchHit(
                    doc.get(F_ID),
                    doc.get(F_KIND),
                    doc.get(F_LABEL),
                    doc.get(F_FQN),
                    doc.get(F_DECLARING),
                    sd.score));
            }
            return results;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Document builders
    // ─────────────────────────────────────────────────────────────────────────

    private Document buildTypeDoc(CodeType t) {
        Document doc = new Document();
        doc.add(new StringField(F_ID,          safe(t.getId()),         Field.Store.YES));
        doc.add(new StringField(F_KIND,        "TYPE",                   Field.Store.YES));
        doc.add(new StringField(F_LABEL,       safe(t.getSimpleName()), Field.Store.YES));
        doc.add(new StringField(F_FQN,         safe(t.getFqn()),        Field.Store.YES));
        doc.add(new TextField(F_SIMPLE_NAME,   safe(t.getSimpleName()), Field.Store.YES));
        doc.add(new StringField(F_DECLARING,   safe(t.getPackageFqn()), Field.Store.YES));
        doc.add(new TextField(F_SEARCH,
            buildSearchText(t.getSimpleName(), t.getFqn(),
                            t.getPackageFqn(), t.getKind()), Field.Store.NO));
        return doc;
    }

    private Document buildMethodDoc(CodeMethod m) {
        Document doc = new Document();
        doc.add(new StringField(F_ID,         safe(m.getId()),             Field.Store.YES));
        doc.add(new StringField(F_KIND,       "METHOD",                     Field.Store.YES));
        doc.add(new StringField(F_LABEL,      safe(m.getSimpleName()),     Field.Store.YES));
        doc.add(new StringField(F_FQN,        safe(m.getFqn()),            Field.Store.YES));
        doc.add(new TextField(F_SIMPLE_NAME,  safe(m.getSimpleName()),     Field.Store.YES));
        doc.add(new StringField(F_DECLARING,  safe(m.getDeclaringTypeFqn()), Field.Store.YES));
        doc.add(new TextField(F_SEARCH,
            buildSearchText(m.getSimpleName(), m.getFqn(),
                            m.getDeclaringTypeFqn(), m.getReturnType()), Field.Store.NO));
        return doc;
    }

    private Document buildFieldDoc(CodeField f) {
        Document doc = new Document();
        doc.add(new StringField(F_ID,         safe(f.getId()),               Field.Store.YES));
        doc.add(new StringField(F_KIND,       "FIELD",                        Field.Store.YES));
        doc.add(new StringField(F_LABEL,      safe(f.getSimpleName()),       Field.Store.YES));
        doc.add(new StringField(F_FQN,        safe(f.getFqn()),              Field.Store.YES));
        doc.add(new TextField(F_SIMPLE_NAME,  safe(f.getSimpleName()),       Field.Store.YES));
        doc.add(new StringField(F_DECLARING,  safe(f.getDeclaringTypeFqn()), Field.Store.YES));
        doc.add(new TextField(F_SEARCH,
            buildSearchText(f.getSimpleName(), f.getFqn(),
                            f.getDeclaringTypeFqn(), f.getFieldType()), Field.Store.NO));
        return doc;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String buildSearchText(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                sb.append(p).append(' ');
                // Also tokenise camelCase: "placeOrder" → "place Order"
                sb.append(p.replaceAll("([A-Z])", " $1")).append(' ');
                // And dot-separated: "com.example.Foo" → "com example Foo"
                sb.append(p.replace('.', ' ')).append(' ');
            }
        }
        return sb.toString().trim();
    }

    private String safe(String s) { return s == null ? "" : s; }

    // ─────────────────────────────────────────────────────────────────────────
    // Value object
    // ─────────────────────────────────────────────────────────────────────────

    /** Lightweight search result — returned by /api/search. */
    public static class SearchHit {
        public final String id;
        public final String kind;          // TYPE | METHOD | FIELD
        public final String label;         // simple name
        public final String fqn;
        public final String declaringType; // parent type or package FQN
        public final float  score;

        public SearchHit(String id, String kind, String label,
                         String fqn, String declaringType, float score) {
            this.id            = id;
            this.kind          = kind;
            this.label         = label;
            this.fqn           = fqn;
            this.declaringType = declaringType;
            this.score         = score;
        }
    }
}
