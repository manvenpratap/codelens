package com.codelens.analysis;

import com.codelens.core.model.CodeRelationship;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.EdgeReversedGraph;
import org.jgrapht.traverse.BreadthFirstIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds an in-memory directed call graph from CALLS relationships and answers
 * two queries:
 *
 *   callees(m, depth) — all methods that m directly or transitively calls
 *   callers(m, depth) — all methods that directly or transitively call m
 *
 * The graph is rebuilt whenever {@link #rebuild(List, List)} is called
 * (typically after a scan completes).
 *
 * Unresolved references ("~scope.method") are resolved by heuristic name-match
 * against the known method FQN set before the graph is populated.
 */
public class CallGraphAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(CallGraphAnalyzer.class);

    /** Directed graph: edge from → to means "from calls to". */
    private Graph<String, DefaultEdge> callGraph =
        new DefaultDirectedGraph<>(DefaultEdge.class);

    /** Index of all known method FQNs, grouped by simple name for heuristic resolution. */
    private Map<String, List<String>> methodsBySimpleName = new HashMap<>();

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Rebuilds the call graph from scratch.
     *
     * @param allMethodFqns    every method FQN discovered during the scan
     * @param callRelationships CALLS relationships (may include "~" prefixed targets)
     */
    public synchronized void rebuild(List<String> allMethodFqns,
                                     List<CodeRelationship> callRelationships) {
        Graph<String, DefaultEdge> g = new DefaultDirectedGraph<>(DefaultEdge.class);
        Map<String, List<String>> byName = new HashMap<>();

        // Populate vertex set
        for (String fqn : allMethodFqns) {
            g.addVertex(fqn);
            String simpleName = simpleMethodName(fqn);
            byName.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(fqn);
        }

        // Populate edges, resolving "~" prefixed targets where possible
        for (CodeRelationship rel : callRelationships) {
            if (!"CALLS".equals(rel.getKind())) continue;

            String from = rel.getFromEntityFqn();
            String to   = rel.getToEntityFqn();

            // Resolve unresolved target
            if (to.startsWith("~")) {
                to = resolve(to, byName);
            }
            if (to == null || to.startsWith("~")) continue; // still unresolved

            // Add vertices if not already present (external methods)
            g.addVertex(from);
            g.addVertex(to);

            try { g.addEdge(from, to); }
            catch (Exception e) { /* duplicate edge – ignore */ }
        }

        this.callGraph         = g;
        this.methodsBySimpleName = byName;
        log.info("Call graph rebuilt: {} vertices, {} edges",
            g.vertexSet().size(), g.edgeSet().size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public query API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all methods reachable from {@code methodFqn} (i.e. what it calls),
     * as a list of {@link GraphNode} objects with depth information.
     * BFS limited to {@code maxDepth} hops.
     */
    public List<GraphNode> callees(String methodFqn, int maxDepth) {
        return bfs(callGraph, methodFqn, maxDepth, "callee");
    }

    /**
     * Returns all methods that can reach {@code methodFqn} (i.e. its callers),
     * by traversing the reversed graph.
     */
    public List<GraphNode> callers(String methodFqn, int maxDepth) {
        Graph<String, DefaultEdge> reversed = new EdgeReversedGraph<>(callGraph);
        return bfs(reversed, methodFqn, maxDepth, "caller");
    }

    /**
     * Builds a full {@link GraphView} suitable for JSON serialisation and
     * rendering by the frontend graph canvas.
     */
    public GraphView callHierarchyView(String rootFqn, int depth) {
        List<GraphNode> calleeNodes = callees(rootFqn, depth);
        List<GraphNode> callerNodes = callers(rootFqn, depth);

        Set<String> seen = new HashSet<>();
        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> edges    = new ArrayList<>();

        // Root node
        GraphNode root = new GraphNode(rootFqn, label(rootFqn), "root", "METHOD");
        allNodes.add(root);
        seen.add(rootFqn);

        // Callee subtree
        for (GraphNode n : calleeNodes) {
            if (seen.add(n.getId())) allNodes.add(n);
        }

        // Caller subtree
        for (GraphNode n : callerNodes) {
            if (seen.add(n.getId())) allNodes.add(n);
        }

        // Edges — walk the graph and emit edges between nodes we've included
        Graph<String, DefaultEdge> g = callGraph;
        for (String v : seen) {
            if (!g.containsVertex(v)) continue;
            for (DefaultEdge e : g.outgoingEdgesOf(v)) {
                String tgt = g.getEdgeTarget(e);
                if (seen.contains(tgt)) {
                    edges.add(new GraphEdge(v, tgt, "CALLS"));
                }
            }
        }

        return new GraphView(rootFqn, allNodes, edges);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<GraphNode> bfs(Graph<String, DefaultEdge> g, String start,
                                 int maxDepth, String role) {
        if (!g.containsVertex(start)) return Collections.emptyList();

        List<GraphNode> result = new ArrayList<>();
        BreadthFirstIterator<String, DefaultEdge> it =
            new BreadthFirstIterator<>(g, start);

        while (it.hasNext()) {
            String v    = it.next();
            int    depth = it.getDepth(v);
            if (v.equals(start)) continue;        // skip root itself
            if (depth > maxDepth) break;
            result.add(new GraphNode(v, label(v), role, "METHOD"));
        }
        return result;
    }

    /**
     * Heuristic resolution of an unresolved call reference.
     * "~repository.save" → looks for any known method with simple name "save".
     * Prefers a candidate whose declaring type name matches the scope token.
     */
    private String resolve(String unresolved, Map<String, List<String>> byName) {
        // "~repository.save" → methodName = "save", scopeHint = "repository"
        String stripped = unresolved.substring(1); // remove "~"
        int dot = stripped.lastIndexOf('.');
        if (dot < 0) return null;
        String scopeHint  = stripped.substring(0, dot).toLowerCase();
        String methodName = stripped.substring(dot + 1);

        List<String> candidates = byName.getOrDefault(methodName, Collections.emptyList());
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        // Multiple candidates — prefer one whose type name contains the scope hint
        return candidates.stream()
            .filter(c -> c.toLowerCase().contains(scopeHint))
            .findFirst()
            .orElse(candidates.get(0));
    }

    /** Extract method simple name from FQN "com.example.Foo.bar(String,int)" → "bar". */
    private String simpleMethodName(String fqn) {
        int paren = fqn.indexOf('(');
        String base = (paren > 0) ? fqn.substring(0, paren) : fqn;
        int dot = base.lastIndexOf('.');
        return (dot >= 0) ? base.substring(dot + 1) : base;
    }

    /** Short display label: "OrderService.placeOrder" from full FQN. */
    private String label(String fqn) {
        int paren = fqn.indexOf('(');
        String base = (paren > 0) ? fqn.substring(0, paren) : fqn;
        String[] parts = base.split("\\.");
        if (parts.length >= 2) return parts[parts.length - 2] + "." + parts[parts.length - 1];
        return base;
    }

    // ── Value objects ─────────────────────────────────────────────────────────

    /** A node in the rendered graph. */
    public static class GraphNode {
        public final String id;
        public final String label;
        public final String role;   // root | caller | callee
        public final String type;   // METHOD | FIELD | TYPE

        public GraphNode(String id, String label, String role, String type) {
            this.id = id; this.label = label; this.role = role; this.type = type;
        }
    }

    /** A directed edge in the rendered graph. */
    public static class GraphEdge {
        public final String source;
        public final String target;
        public final String kind;

        public GraphEdge(String source, String target, String kind) {
            this.source = source; this.target = target; this.kind = kind;
        }
    }

    /** Full graph payload sent to the frontend. */
    public static class GraphView {
        public final String           rootId;
        public final List<GraphNode>  nodes;
        public final List<GraphEdge>  edges;

        public GraphView(String rootId, List<GraphNode> nodes, List<GraphEdge> edges) {
            this.rootId = rootId; this.nodes = nodes; this.edges = edges;
        }
    }
}
