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

    /** Immutable snapshot of field-related relationships. */
    private List<CodeRelationship> fieldRels = Collections.emptyList();
    private List<CodeRelationship> callRels  = Collections.emptyList();

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Update the internal relationship snapshot.
     * Called after each scan completes.
     */
    public synchronized void rebuild(List<CodeRelationship> allRelationships) {
        this.fieldRels = allRelationships.stream()
            .filter(r -> "READS_FIELD".equals(r.getKind())
                      || "WRITES_FIELD".equals(r.getKind()))
            .collect(Collectors.toList());
        this.callRels = allRelationships.stream()
            .filter(r -> "CALLS".equals(r.getKind()))
            .collect(Collectors.toList());
        log.info("FieldImpactAnalyzer updated: {} field rels", fieldRels.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public query API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns an {@link ImpactView} for {@code fieldFqn}, containing every
     * method that reads, writes, or propagates its value.
     */
    public ImpactView analyse(String fieldFqn) {
        List<String> readers    = new ArrayList<>();
        List<String> writers    = new ArrayList<>();
        List<String> propagators = new ArrayList<>();

        for (CodeRelationship rel : fieldRels) {
            if (!fieldFqn.equals(rel.getToEntityFqn())) continue;

            switch (rel.getKind()) {
                case "READS_FIELD"  -> readers.add(rel.getFromEntityFqn());
                case "WRITES_FIELD" -> writers.add(rel.getFromEntityFqn());
            }
        }

        // Propagators: readers that also call another method
        Set<String> callingMethods = callRels.stream()
            .map(CodeRelationship::getFromEntityFqn)
            .collect(Collectors.toSet());
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
