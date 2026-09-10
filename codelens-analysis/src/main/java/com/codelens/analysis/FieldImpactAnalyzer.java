package com.codelens.analysis;

import com.codelens.core.model.CodeRelationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyses which methods READ, WRITE, or PROPAGATE a given field.
 *
 * "Propagation" is defined as: a method reads the field AND passes it into
 * another method call (detected by the method both reading the field and
 * calling at least one other method).
 *
 * This is a lightweight, graph-free analysis — the results are built from
 * the raw relationship lists rather than the JGraphT graph structure.
 */
public class FieldImpactAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(FieldImpactAnalyzer.class);

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String phase, int current, int total, String detail);
    }

    /** Immutable snapshot of field-related relationships. */
    private List<CodeRelationship> fieldRels      = Collections.emptyList();
    private Set<String>            callingMethods = Collections.emptySet();
    private Map<String, List<CodeRelationship>> fieldRelIndex = Collections.emptyMap();

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Update the internal relationship snapshot with lean field relationships and caller set, with progress callback.
     */
    public synchronized void rebuild(List<CodeRelationship> fieldRelationships, Set<String> callingMethodFqns, ProgressListener listener) {
        this.fieldRels = fieldRelationships != null ? fieldRelationships : Collections.emptyList();
        this.callingMethods = callingMethodFqns != null ? callingMethodFqns : Collections.emptySet();

        Map<String, List<CodeRelationship>> index = new HashMap<>();
        int total = this.fieldRels.size();
        int count = 0;
        for (CodeRelationship rel : this.fieldRels) {
            if (rel != null && rel.getToEntityFqn() != null) {
                index.computeIfAbsent(rel.getToEntityFqn(), k -> new ArrayList<>(4)).add(rel);
            }
            count++;
            if (listener != null && (count % 250 == 0 || count == total)) {
                String target = (rel != null && rel.getToEntityFqn() != null) ? rel.getToEntityFqn() : "field";
                int lastDot = target.lastIndexOf('.');
                String shortTarget = lastDot >= 0 ? target.substring(lastDot + 1) : target;
                listener.onProgress("Field Impact: Indexing Relations", count, total,
                    String.format("Indexed %,d / %,d field relations (%s)", count, total, shortTarget));
            }
        }
        this.fieldRelIndex = Collections.unmodifiableMap(index);
        log.info("FieldImpactAnalyzer updated: {} field rels ({} unique fields), {} calling methods",
            fieldRels.size(), index.size(), callingMethods.size());
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
        List<String> readers    = new ArrayList<>();
        List<String> writers    = new ArrayList<>();
        List<String> propagators = new ArrayList<>();

        List<CodeRelationship> relsForField = fieldRelIndex.get(fieldFqn);
        if (relsForField != null) {
            for (CodeRelationship rel : relsForField) {
                switch (rel.getKind()) {
                    case "READS_FIELD"  -> readers.add(rel.getFromEntityFqn());
                    case "WRITES_FIELD" -> writers.add(rel.getFromEntityFqn());
                }
            }
        } else if (fieldRelIndex.isEmpty() && !fieldRels.isEmpty()) {
            // Fallback for safety if index was not populated
            for (CodeRelationship rel : fieldRels) {
                if (!fieldFqn.equals(rel.getToEntityFqn())) continue;
                switch (rel.getKind()) {
                    case "READS_FIELD"  -> readers.add(rel.getFromEntityFqn());
                    case "WRITES_FIELD" -> writers.add(rel.getFromEntityFqn());
                }
            }
        }

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
