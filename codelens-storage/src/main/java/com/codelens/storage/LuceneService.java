package com.codelens.storage;

import com.codelens.core.model.*;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.NIOFSDirectory;
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
    @SuppressWarnings("unused") // retained as schema documentation
    private static final String F_PACKAGE       = "packageFqn";
    private static final String F_DECLARING     = "declaringType";
    @SuppressWarnings("unused") // retained as schema documentation
    private static final String F_EXTRA         = "extra";      // modifiers, return type, etc.
    private static final String F_SEARCH        = "search";     // all-in-one search field

    private static final String[] SEARCH_FIELDS = { F_SIMPLE_NAME, F_FQN, F_SEARCH };
    private static final Map<String, Float> FIELD_BOOSTS;
    static {
        Map<String, Float> b = new LinkedHashMap<>();
        b.put(F_SIMPLE_NAME, 3.0f);
        b.put(F_FQN,         2.0f);
        b.put(F_SEARCH,      1.0f);
        FIELD_BOOSTS = Collections.unmodifiableMap(b);
    }

    private final Path indexDir;
    private FSDirectory     directory;
    private StandardAnalyzer analyzer;
    private IndexWriter      writer;
    private SearcherManager  searcherManager;
    private ThreadLocal<MultiFieldQueryParser> queryParser;

    public LuceneService(String dataDir) {
        this.indexDir = Paths.get(dataDir, "lucene-index");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /** Opens (or creates) the index directory and initialises the IndexWriter and SearcherManager. */
    public void initialize() throws IOException {
        Files.createDirectories(indexDir);
        try {
            directory = new NIOFSDirectory(indexDir);
        } catch (Throwable t) {
            directory = FSDirectory.open(indexDir);
        }
        analyzer  = new StandardAnalyzer();
        queryParser = ThreadLocal.withInitial(() -> {
            MultiFieldQueryParser p = new MultiFieldQueryParser(SEARCH_FIELDS, analyzer, FIELD_BOOSTS);
            p.setDefaultOperator(QueryParser.Operator.AND);
            p.setAllowLeadingWildcard(true);
            return p;
        });
        IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        cfg.setRAMBufferSizeMB(256.0);
        writer    = new IndexWriter(directory, cfg);
        searcherManager = new SearcherManager(writer, true, true, new SearcherFactory());
        log.info("Lucene index initialised at {} with SearcherManager", indexDir);
    }

    /** Flush and close the writer; release OS file handles. */
    public void close() {
        try {
            if (queryParser != null) queryParser.remove();
            if (searcherManager != null) searcherManager.close();
            if (writer != null) writer.close();
            if (directory != null) directory.close();
        } catch (IOException e) {
            log.warn("Error closing Lucene index: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Indexing
    // ─────────────────────────────────────────────────────────────────────────

    /** Prepares for a full index rebuild by clearing existing documents. */
    public synchronized void prepareIndexRebuild() throws IOException {
        writer.deleteAll();
    }

    /**
     * Appends a batch of types, methods, and fields directly into the Lucene RAM buffer.
     * Used during full repository scans after deleteAll() to achieve maximum write throughput
     * without redundant term dictionary searches or delete queue processing.
     */
    public synchronized void addBatch(List<CodeType>   types,
                                      List<CodeMethod> methods,
                                      List<CodeField>  fields) throws IOException {
        int estimatedSize = (types != null ? types.size() : 0)
                          + (methods != null ? methods.size() : 0)
                          + (fields != null ? fields.size() : 0);
        if (estimatedSize == 0) return;

        List<Document> docs = new ArrayList<>(estimatedSize);
        if (types != null) {
            for (CodeType t : types) {
                if (t.getId() != null && !t.getId().isEmpty()) {
                    docs.add(buildTypeDoc(t));
                }
            }
        }
        if (methods != null) {
            for (CodeMethod m : methods) {
                if (m.getId() != null && !m.getId().isEmpty()) {
                    docs.add(buildMethodDoc(m));
                }
            }
        }
        if (fields != null) {
            for (CodeField f : fields) {
                if (f.getId() != null && !f.getId().isEmpty()) {
                    docs.add(buildFieldDoc(f));
                }
            }
        }
        if (!docs.isEmpty()) {
            writer.addDocuments(docs);
        }
    }

    /** Indexes a batch of types, methods, and fields incrementally. Uses updateDocument for strict idempotency. */
    public synchronized void indexBatch(List<CodeType>   types,
                                        List<CodeMethod> methods,
                                        List<CodeField>  fields) throws IOException {
        if (types != null) {
            for (CodeType t : types) {
                if (t.getId() != null && !t.getId().isEmpty()) {
                    writer.updateDocument(new Term(F_ID, t.getId()), buildTypeDoc(t));
                }
            }
        }
        if (methods != null) {
            for (CodeMethod m : methods) {
                if (m.getId() != null && !m.getId().isEmpty()) {
                    writer.updateDocument(new Term(F_ID, m.getId()), buildMethodDoc(m));
                }
            }
        }
        if (fields != null) {
            for (CodeField f : fields) {
                if (f.getId() != null && !f.getId().isEmpty()) {
                    writer.updateDocument(new Term(F_ID, f.getId()), buildFieldDoc(f));
                }
            }
        }
    }

    /** Commits all indexed batches to disk and refreshes the SearcherManager. */
    public synchronized void finishIndexRebuild() throws IOException {
        writer.commit();
        if (searcherManager != null) {
            searcherManager.maybeRefresh();
        }
        log.info("Lucene index rebuild completed and committed to disk");
    }

    /**
     * Replaces the entire index content with the given entity lists.
     * Called once per scan, after the H2 batch insert succeeds.
     */
    public void rebuildIndex(List<CodeType>   types,
                             List<CodeMethod> methods,
                             List<CodeField>  fields) throws IOException {
        prepareIndexRebuild();
        indexBatch(types, methods, fields);
        finishIndexRebuild();
        log.info("Lucene index rebuilt: {} types, {} methods, {} fields",
            types != null ? types.size() : 0,
            methods != null ? methods.size() : 0,
            fields != null ? fields.size() : 0);
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Search
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full-text search across all indexed entities using persistent in-memory SearcherManager.
     *
     * @param queryStr  raw user query; supports wildcards (foo*) and phrases ("place order")
     * @param maxHits   maximum number of results to return (capped at 100)
     * @return list of lightweight {@link SearchHit} objects
     */
    public List<SearchHit> search(String queryStr, int maxHits) throws Exception {
        if (queryStr == null || queryStr.isBlank()) return Collections.emptyList();
        maxHits = Math.min(maxHits, 100);

        if (searcherManager == null) {
            return Collections.emptyList();
        }

        IndexSearcher searcher = searcherManager.acquire();
        try {
            MultiFieldQueryParser parser = (queryParser != null) ? queryParser.get() : null;
            if (parser == null) {
                parser = new MultiFieldQueryParser(SEARCH_FIELDS, analyzer, FIELD_BOOSTS);
                parser.setDefaultOperator(QueryParser.Operator.AND);
                parser.setAllowLeadingWildcard(true);
            }

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
                org.apache.lucene.document.Document doc = searcher.storedFields().document(sd.doc);
                results.add(new SearchHit(
                    doc.get(F_ID),
                    doc.get(F_KIND),
                    doc.get(F_LABEL),
                    doc.get(F_FQN),
                    doc.get(F_DECLARING),
                    sd.score));
            }
            return results;
        } finally {
            searcherManager.release(searcher);
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
        StringBuilder sb = new StringBuilder(128);
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                sb.append(p).append(' ');
                // In-place camelCase tokenization without regex compile overhead: "placeOrder" → "place Order"
                appendCamelCaseSplit(sb, p);
                sb.append(' ');
                // In-place dot-separated tokenization: "com.example.Foo" → "com example Foo"
                for (int i = 0; i < p.length(); i++) {
                    char c = p.charAt(i);
                    sb.append(c == '.' ? ' ' : c);
                }
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }

    private static void appendCamelCaseSplit(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(s.charAt(i - 1))) {
                sb.append(' ');
            }
            sb.append(c);
        }
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
