package com.codelens.analysis;

import com.codelens.core.model.CodeRelationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Analyses which methods READ, WRITE, or PROPAGATE a given field.
 *
 * "Propagation" is defined as: a method reads the field AND passes it into
 * another method call (detected by the method both reading the field and
 * calling at least one other method).
 *
 * This is a lightweight, graph-free analysis — the results are built from
 * streaming field access tuples rather than heavyweight object graphs,
 * minimizing heap footprint and eliminating GC pauses on large codebases.
 */
public class FieldImpactAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(FieldImpactAnalyzer.class);

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String phase, int current, int total, String detail);
    }

    @FunctionalInterface
    public interface FieldStreamer {
        void stream(FieldConsumer consumer) throws Exception;
    }

    @FunctionalInterface
    public interface FieldConsumer {
        void accept(String fromEntityFqn, String toEntityFqn, String kind);
    }

    /**
     * Compact storage for field access relationships.
     * Replaces heavyweight CodeRelationship objects with lightweight string lists.
     */
    public static class CompactFieldImpact {
        public final List<String> readers = new ArrayList<>(2);
        public final List<String> writers = new ArrayList<>(2);
    }

    private Set<String> callingMethods = Collections.emptySet();
    private Map<String, CompactFieldImpact> fieldImpactMap = Collections.emptyMap();
    private int totalRelsCount = 0;

    // ─────────────────────────────────────────────────────────────────────────
    // Rebuild APIs
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Rebuilds field impact index directly from a streaming cursor without instantiating
     * intermediate CodeRelationship objects.
     */
    public synchronized void rebuildWithStream(FieldStreamer streamer,
                                               int totalExpectedRels,
                                               Set<String> callingMethodFqns,
                                               ProgressListener listener) throws Exception {
        this.callingMethods = callingMethodFqns != null ? callingMethodFqns : Collections.emptySet();
        Map<String, CompactFieldImpact> map = new HashMap<>(Math.max(1024, totalExpectedRels > 0 ? totalExpectedRels / 4 : 4096));
        int[] count = new int[]{0};
        int stride = totalExpectedRels > 0 ? Math.min(5000, Math.max(1, totalExpectedRels / 20)) : 1000;

        if (streamer != null) {
            streamer.stream((from, to, kind) -> {
                if (from == null || to == null || kind == null) return;
                CompactFieldImpact impact = map.computeIfAbsent(to, k -> new CompactFieldImpact());
                if ("READS_FIELD".equals(kind)) {
                    impact.readers.add(from);
                } else if ("WRITES_FIELD".equals(kind)) {
                    impact.writers.add(from);
                }
                count[0]++;
                if (listener != null && (count[0] % stride == 0 || count[0] == totalExpectedRels)) {
                    int lastDot = to.lastIndexOf('.');
                    String shortTarget = lastDot >= 0 ? to.substring(lastDot + 1) : to;
                    listener.onProgress("Field Impact: Indexing Relations", count[0], totalExpectedRels,
                        String.format("Indexed %,d / %,d field relations (%s)", count[0], totalExpectedRels > 0 ? totalExpectedRels : count[0], shortTarget));
                }
            });
        }

        this.fieldImpactMap = Collections.unmodifiableMap(map);
        this.totalRelsCount = count[0];
        log.info("FieldImpactAnalyzer updated via streaming: {} field rels ({} unique fields), {} calling methods",
            count[0], map.size(), this.callingMethods.size());
    }

    public synchronized void rebuildWithStream(FieldStreamer streamer,
                                               int totalExpectedRels,
                                               Set<String> callingMethodFqns) throws Exception {
        rebuildWithStream(streamer, totalExpectedRels, callingMethodFqns, null);
    }

    /**
     * Update the internal relationship snapshot with lean field relationships and caller set, with progress callback.
     * Backwards-compatible overload for pre-fetched lists.
     */
    public synchronized void rebuild(List<CodeRelationship> fieldRelationships,
                                     Set<String> callingMethodFqns,
                                     ProgressListener listener) {
        int total = fieldRelationships != null ? fieldRelationships.size() : 0;
        try {
            rebuildWithStream(consumer -> {
                if (fieldRelationships != null) {
                    for (CodeRelationship rel : fieldRelationships) {
                        if (rel != null) {
                            consumer.accept(rel.getFromEntityFqn(), rel.getToEntityFqn(), rel.getKind());
                        }
                    }
                }
            }, total, callingMethodFqns, listener);
        } catch (Exception e) {
            log.error("Failed to rebuild field impact analyzer", e);
        }
    }

    /**
     * Update the internal relationship snapshot with lean field relationships and caller set.
     */
    public synchronized void rebuild(List<CodeRelationship> fieldRelationships, Set<String> callingMethodFqns) {
        rebuild(fieldRelationships, callingMethodFqns, null);
    }

    /**
     * Update the internal relationship snapshot from all relationships (legacy/test overload).
     */
    public synchronized void rebuild(List<CodeRelationship> allRelationships) {
        if (allRelationships == null) {
            rebuild(Collections.emptyList(), Collections.emptySet(), null);
            return;
        }
        List<CodeRelationship> fields = new ArrayList<>();
        Set<String> callers = new HashSet<>();
        for (CodeRelationship r : allRelationships) {
            if ("READS_FIELD".equals(r.getKind()) || "WRITES_FIELD".equals(r.getKind())) {
                fields.add(r);
            } else if ("CALLS".equals(r.getKind())) {
                callers.add(r.getFromEntityFqn());
            }
        }
        rebuild(fields, callers, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public query API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns an {@link ImpactView} for {@code fieldFqn}, containing every
     * method that reads, writes, or propagates its value.
     */
    public ImpactView analyse(String fieldFqn) {
        return analyse(fieldFqn, 1, null);
    }

    /**
     * Returns an {@link ImpactView} for {@code fieldFqn} with multi-hop
     * caller propagation up to {@code depth} hops.
     */
    public ImpactView analyse(String fieldFqn, int depth, CallGraphAnalyzer callGraphAnalyzer) {
        CompactFieldImpact impact = fieldImpactMap.get(fieldFqn);
        List<String> readers    = impact != null ? new ArrayList<>(impact.readers) : new ArrayList<>();
        List<String> writers    = impact != null ? new ArrayList<>(impact.writers) : new ArrayList<>();
        List<String> propagators = new ArrayList<>();

        // Propagators: readers that also call another method
        for (String reader : readers) {
            if (callingMethods.contains(reader)) {
                propagators.add(reader);
            }
        }

        // Build graph view
        List<CallGraphAnalyzer.GraphNode> nodes = new ArrayList<>();
        List<CallGraphAnalyzer.GraphEdge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Field node
        nodes.add(new CallGraphAnalyzer.GraphNode(fieldFqn, lastName(fieldFqn), "field", "FIELD"));
        seen.add(fieldFqn);

        addNodes(nodes, edges, readers,     seen, fieldFqn, "READS_FIELD",  "reader");
        addNodes(nodes, edges, writers,     seen, fieldFqn, "WRITES_FIELD", "writer");
        addNodes(nodes, edges, propagators, seen, fieldFqn, "CALLS",        "propagator");

        // If depth > 1 and callGraphAnalyzer is available, trace upstream callers of writers & propagators
        if (depth > 1 && callGraphAnalyzer != null) {
            int callerDepth = depth - 1;
            Set<String> directMethods = new LinkedHashSet<>();
            directMethods.addAll(writers);
            directMethods.addAll(propagators);
            directMethods.addAll(readers);

            for (String methodFqn : directMethods) {
                List<CallGraphAnalyzer.GraphNode> upstreamCallers = callGraphAnalyzer.callers(methodFqn, callerDepth);
                for (CallGraphAnalyzer.GraphNode callerNode : upstreamCallers) {
                    if (seen.add(callerNode.id)) {
                        nodes.add(new CallGraphAnalyzer.GraphNode(callerNode.id, callerNode.label, "caller", "METHOD"));
                    }
                }
            }

            // Also attach resolved call edges among all included methods
            edges.addAll(callGraphAnalyzer.getEdgesBetween(seen));
        }

        return new ImpactView(fieldFqn, readers, writers, propagators,
                              new CallGraphAnalyzer.GraphView(fieldFqn, nodes, edges));
    }

    public int getTotalRelationshipsCount() {
        return totalRelsCount;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void addNodes(List<CallGraphAnalyzer.GraphNode> nodes,
                          List<CallGraphAnalyzer.GraphEdge> edges,
                          List<String> methods,
                          Set<String> seen,
                          String fieldFqn,
                          String edgeKind,
                          String role) {
        for (String m : methods) {
            if (seen.add(m)) {
                nodes.add(new CallGraphAnalyzer.GraphNode(m, lastName(m), role, "METHOD"));
            }
            edges.add(new CallGraphAnalyzer.GraphEdge(m, fieldFqn, edgeKind));
        }
    }

    private String lastName(String fqn) {
        int paren = fqn.indexOf('(');
        String base = paren > 0 ? fqn.substring(0, paren) : fqn;
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot + 1) : base;
    }

    // ── Value objects ─────────────────────────────────────────────────────────

    public static class ImpactView {
        public final String fieldFqn;
        public final List<String> readers;
        public final List<String> writers;
        public final List<String> propagators;
        public final CallGraphAnalyzer.GraphView graph;

        public ImpactView(String fieldFqn, List<String> readers, List<String> writers,
                          List<String> propagators, CallGraphAnalyzer.GraphView graph) {
            this.fieldFqn    = fieldFqn;
            this.readers     = readers;
            this.writers     = writers;
            this.propagators = propagators;
            this.graph       = graph;
        }
    }
}
