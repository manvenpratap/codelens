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


    // ─────────────────────────────────────────────────────────────────────────

    /** Functional interface for streaming call edges during graph construction. */
    @FunctionalInterface
    public interface EdgeConsumer {
        void accept(String from, String to);
    }

    /** Functional interface for providing an edge stream. */
    @FunctionalInterface
    public interface EdgeStreamer {
        void stream(EdgeConsumer consumer) throws Exception;
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Rebuilds the call graph by streaming edges directly from a database cursor.
     */
    public synchronized void rebuild(List<String> allMethodFqns, EdgeStreamer edgeStreamer) throws Exception {
        Graph<String, DefaultEdge> g = new DefaultDirectedGraph<>(DefaultEdge.class);
        Map<String, List<String>> byName = new HashMap<>(allMethodFqns.size());

        // Populate vertex set with interned strings to deduplicate repeated package/class prefixes
        for (String fqn : allMethodFqns) {
            String interned = fqn.intern();
            g.addVertex(interned);
            String simpleName = simpleMethodName(interned).intern();
            byName.computeIfAbsent(simpleName, k -> new ArrayList<>(2)).add(interned);
        }

        // Stream edges, resolving "~" prefixed targets
        if (edgeStreamer != null) {
            edgeStreamer.stream((rawFrom, rawTo) -> {
                if (rawFrom == null || rawTo == null) return;
                String from = rawFrom.intern();
                String to   = rawTo;

                if (to.startsWith("~")) {
                    to = resolve(to, byName);
                }
                if (to == null || to.startsWith("~")) return;
                to = to.intern();

                g.addVertex(from);
                g.addVertex(to);

                try { g.addEdge(from, to); }
                catch (Exception ignored) { /* duplicate edge */ }
            });
        }

        this.callGraph = g;
        log.info("Call graph rebuilt: {} vertices, {} edges",
            g.vertexSet().size(), g.edgeSet().size());
    }

    /**
     * Rebuilds the call graph from scratch (in-memory list overload for tests / backwards compatibility).
     *
     * @param allMethodFqns    every method FQN discovered during the scan
     * @param callRelationships CALLS relationships (may include "~" prefixed targets)
     */
    public synchronized void rebuild(List<String> allMethodFqns,
                                     List<CodeRelationship> callRelationships) {
        try {
            rebuild(allMethodFqns, consumer -> {
                if (callRelationships != null) {
                    for (CodeRelationship rel : callRelationships) {
                        if ("CALLS".equals(rel.getKind())) {
                            consumer.accept(rel.getFromEntityFqn(), rel.getToEntityFqn());
                        }
                    }
                }
            });
        } catch (Exception e) {
            log.error("Failed to rebuild call graph", e);
        }
    }


    /**
     * Identifies if a method FQN represents a trivial POJO accessor / getter / setter / boilerplate method.
     */
    public static boolean isPojoOrAccessor(String methodFqn) {
        if (methodFqn == null || methodFqn.isEmpty()) return false;
        int paren = methodFqn.indexOf('(');
        String base = (paren > 0) ? methodFqn.substring(0, paren) : methodFqn;
        int dot = base.lastIndexOf('.');
        String name = (dot >= 0) ? base.substring(dot + 1) : base;
        if (name.isEmpty()) return false;

        // Standard Object boilerplate
        if ("toString".equals(name) || "hashCode".equals(name) || "equals".equals(name) || "canEqual".equals(name) || "getClass".equals(name)) {
            return true;
        }

        // Standard Getters / Setters / Is / Has
        if (name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3))) {
            return true;
        }
        if (name.length() > 3 && name.startsWith("set") && Character.isUpperCase(name.charAt(3))) {
            return true;
        }
        if (name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
            return true;
        }
        if (name.length() > 3 && name.startsWith("has") && Character.isUpperCase(name.charAt(3))) {
            return true;
        }

        return false;
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
        return callees(methodFqn, maxDepth, false);
    }

    public List<GraphNode> callees(String methodFqn, int maxDepth, boolean hideGetters) {
        return bfs(callGraph, methodFqn, maxDepth, "callee", hideGetters);
    }

    /**
     * Returns all methods that can reach {@code methodFqn} (i.e. its callers),
     * by traversing the reversed graph.
     */
    public List<GraphNode> callers(String methodFqn, int maxDepth) {
        return callers(methodFqn, maxDepth, false);
    }

    public List<GraphNode> callers(String methodFqn, int maxDepth, boolean hideGetters) {
        Graph<String, DefaultEdge> reversed = new EdgeReversedGraph<>(callGraph);
        return bfs(reversed, methodFqn, maxDepth, "caller", hideGetters);
    }

    /**
     * Returns the number of direct callers (in-degree) for the given method.
     */
    public int callerCount(String methodFqn) {
        if (!callGraph.containsVertex(methodFqn)) return 0;
        return callGraph.inDegreeOf(methodFqn);
    }

    /**
     * Returns the number of direct callees (out-degree) for the given method.
     */
    public int calleeCount(String methodFqn) {
        if (!callGraph.containsVertex(methodFqn)) return 0;
        return callGraph.outDegreeOf(methodFqn);
    }

    /**
     * Returns all CALLS edges between any pairs of vertices in {@code vertexSet}.
     */
    public List<GraphEdge> getEdgesBetween(Set<String> vertexSet) {
        List<GraphEdge> edges = new ArrayList<>();
        Graph<String, DefaultEdge> g = callGraph;
        for (String v : vertexSet) {
            if (!g.containsVertex(v)) continue;
            for (DefaultEdge e : g.outgoingEdgesOf(v)) {
                String tgt = g.getEdgeTarget(e);
                if (vertexSet.contains(tgt)) {
                    edges.add(new GraphEdge(v, tgt, "CALLS"));
                }
            }
        }
        return edges;
    }

    /**
     * Builds a full {@link GraphView} suitable for JSON serialisation and
     * rendering by the frontend graph canvas.
     */
    public GraphView callHierarchyView(String rootFqn, int depth) {
        return callHierarchyView(rootFqn, depth, false);
    }

    public GraphView callHierarchyView(String rootFqn, int depth, boolean hideGetters) {
        List<GraphNode> calleeNodes = callees(rootFqn, depth, hideGetters);
        List<GraphNode> callerNodes = callers(rootFqn, depth, hideGetters);

        Set<String> seen = new HashSet<>();
        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> edges    = new ArrayList<>();

        // Root node
        GraphNode root = new GraphNode(rootFqn, label(rootFqn), "root", "METHOD");
        allNodes.add(root);
        seen.add(rootFqn);

        // Callee subtree
        for (GraphNode n : calleeNodes) {
            if (seen.add(n.id)) allNodes.add(n);
        }

        // Caller subtree
        for (GraphNode n : callerNodes) {
            if (seen.add(n.id)) allNodes.add(n);
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

    public GraphView callersView(String rootFqn, int depth) {
        return callersView(rootFqn, depth, false);
    }

    public GraphView callersView(String rootFqn, int depth, boolean hideGetters) {
        List<GraphNode> callerNodes = callers(rootFqn, depth, hideGetters);

        Set<String> seen = new HashSet<>();
        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> edges    = new ArrayList<>();

        // Root node
        GraphNode root = new GraphNode(rootFqn, label(rootFqn), "root", "METHOD");
        allNodes.add(root);
        seen.add(rootFqn);

        // Caller subtree
        for (GraphNode n : callerNodes) {
            if (seen.add(n.id)) allNodes.add(n);
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

    public GraphView calleesView(String rootFqn, int depth) {
        return calleesView(rootFqn, depth, false);
    }

    public GraphView calleesView(String rootFqn, int depth, boolean hideGetters) {
        List<GraphNode> calleeNodes = callees(rootFqn, depth, hideGetters);

        Set<String> seen = new HashSet<>();
        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> edges    = new ArrayList<>();

        // Root node
        GraphNode root = new GraphNode(rootFqn, label(rootFqn), "root", "METHOD");
        allNodes.add(root);
        seen.add(rootFqn);

        // Callee subtree
        for (GraphNode n : calleeNodes) {
            if (seen.add(n.id)) allNodes.add(n);
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

    /**
     * Complete global view of the entire codebase call graph.
     * Emits all indexed vertices and relationships.
     */
    public GraphView fullGraphView() {
        return fullGraphView(false);
    }

    public GraphView fullGraphView(boolean hideGetters) {
        Graph<String, DefaultEdge> g = callGraph;
        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> edges    = new ArrayList<>();
        Set<String> includedVertices = new HashSet<>();

        for (String v : g.vertexSet()) {
            if (hideGetters && isPojoOrAccessor(v)) continue;
            includedVertices.add(v);
            int inDeg  = g.inDegreeOf(v);
            int outDeg = g.outDegreeOf(v);
            String role;
            if (inDeg == 0 && outDeg > 0) {
                role = "root";
            } else if (inDeg > 0 && outDeg == 0) {
                role = "callee";
            } else if (inDeg > 0 && outDeg == 0) {
                role = "propagator";
            } else {
                role = "default";
            }
            allNodes.add(new GraphNode(v, label(v), role, "METHOD"));
        }

        for (String v : includedVertices) {
            for (DefaultEdge e : g.outgoingEdgesOf(v)) {
                String tgt = g.getEdgeTarget(e);
                if (includedVertices.contains(tgt)) {
                    edges.add(new GraphEdge(v, tgt, "CALLS"));
                }
            }
        }

        return new GraphView("GLOBAL", allNodes, edges);
    }

    /**
     * Architecture-level view aggregating call relationships to Module, Package, or Class level.
     * @param scope "modules", "packages", or "classes" (null = auto-detect based on size)
     * @param filter optional module or package filter
     */
    public GraphView architectureGraphView(String scope, String filter) {
        Graph<String, DefaultEdge> g = callGraph;
        Set<String> allClasses = new TreeSet<>();
        for (String v : g.vertexSet()) {
            allClasses.add(extractClassFqn(v));
        }

        String effectiveScope = scope;
        if (effectiveScope == null || effectiveScope.isEmpty() || "auto".equalsIgnoreCase(effectiveScope)) {
            Set<String> modules = new HashSet<>();
            Set<String> packages = new HashSet<>();
            for (String v : g.vertexSet()) {
                modules.add(extractModuleName(v));
                packages.add(extractPackageFqn(v));
            }
            if (modules.size() > 1 && allClasses.size() > 500) {
                effectiveScope = "modules";
            } else if (packages.size() > 1 && allClasses.size() > 300) {
                effectiveScope = "packages";
            } else {
                effectiveScope = "classes";
            }
        }

        if ("modules".equalsIgnoreCase(effectiveScope)) {
            GraphView modView = moduleArchitectureGraphView();
            if (modView.nodes.size() <= 1 && allClasses.size() > 1) {
                return classArchitectureGraphView(filter);
            }
            return modView;
        } else if ("packages".equalsIgnoreCase(effectiveScope)) {
            GraphView pkgView = packageArchitectureGraphView(filter);
            if (pkgView.nodes.size() <= 1 && allClasses.size() > 1) {
                return classArchitectureGraphView(filter);
            }
            return pkgView;
        } else {
            return classArchitectureGraphView(filter);
        }
    }

    public GraphView architectureGraphView() {
        return architectureGraphView(null, null);
    }

    /** Module-level aggregated graph view (e.g. 50 modules). */
    private GraphView moduleArchitectureGraphView() {
        Graph<String, DefaultEdge> g = callGraph;
        Map<String, Integer> moduleClassCounts = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> moduleCalls = new LinkedHashMap<>();

        for (String v : g.vertexSet()) {
            String mod = extractModuleName(v);
            moduleClassCounts.put(mod, moduleClassCounts.getOrDefault(mod, 0) + 1);

            for (DefaultEdge e : g.outgoingEdgesOf(v)) {
                String tgt = g.getEdgeTarget(e);
                String tgtMod = extractModuleName(tgt);
                if (!mod.equals(tgtMod)) {
                    moduleCalls.computeIfAbsent(mod, k -> new LinkedHashMap<>())
                               .merge(tgtMod, 1, Integer::sum);
                }
            }
        }

        List<GraphNode> nodes = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : moduleClassCounts.entrySet()) {
            String mod = entry.getKey();
            nodes.add(new GraphNode(mod, mod, "module", "MODULE"));
        }

        List<GraphEdge> edges = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> srcEntry : moduleCalls.entrySet()) {
            String src = srcEntry.getKey();
            for (Map.Entry<String, Integer> tgtEntry : srcEntry.getValue().entrySet()) {
                String tgt = tgtEntry.getKey();
                int count = tgtEntry.getValue();
                edges.add(new GraphEdge(src, tgt, count > 1 ? "CALLS (" + count + ")" : "CALLS"));
            }
        }

        return new GraphView("MODULE_ARCHITECTURE", nodes, edges);
    }

    /** Package-level aggregated graph view. */
    private GraphView packageArchitectureGraphView(String moduleFilter) {
        Graph<String, DefaultEdge> g = callGraph;
        Map<String, Integer> pkgClassCounts = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> pkgCalls = new LinkedHashMap<>();

        for (String v : g.vertexSet()) {
            if (moduleFilter != null && !moduleFilter.isEmpty() && !extractModuleName(v).equalsIgnoreCase(moduleFilter)) {
                continue;
            }
            String pkg = extractPackageFqn(v);
            pkgClassCounts.put(pkg, pkgClassCounts.getOrDefault(pkg, 0) + 1);

            for (DefaultEdge e : g.outgoingEdgesOf(v)) {
                String tgt = g.getEdgeTarget(e);
                if (moduleFilter != null && !moduleFilter.isEmpty() && !extractModuleName(tgt).equalsIgnoreCase(moduleFilter)) {
                    continue;
                }
                String tgtPkg = extractPackageFqn(tgt);
                if (!pkg.equals(tgtPkg)) {
                    pkgCalls.computeIfAbsent(pkg, k -> new LinkedHashMap<>())
                            .merge(tgtPkg, 1, Integer::sum);
                }
            }
        }

        List<GraphNode> nodes = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : pkgClassCounts.entrySet()) {
            String pkg = entry.getKey();
            int dot = pkg.lastIndexOf('.');
            String label = (dot >= 0) ? pkg.substring(dot + 1) : pkg;
            nodes.add(new GraphNode(pkg, label, "package", "PACKAGE"));
        }

        List<GraphEdge> edges = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> srcEntry : pkgCalls.entrySet()) {
            String src = srcEntry.getKey();
            for (Map.Entry<String, Integer> tgtEntry : srcEntry.getValue().entrySet()) {
                String tgt = tgtEntry.getKey();
                int count = tgtEntry.getValue();
                edges.add(new GraphEdge(src, tgt, count > 1 ? "CALLS (" + count + ")" : "CALLS"));
            }
        }

        return new GraphView("PACKAGE_ARCHITECTURE", nodes, edges);
    }

    /** Class-level aggregated graph view. */
    private GraphView classArchitectureGraphView(String filter) {
        Graph<String, DefaultEdge> g = callGraph;
        Map<String, Integer> classMethodCounts = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> classCalls = new LinkedHashMap<>();

        for (String v : g.vertexSet()) {
            if (filter != null && !filter.isEmpty()) {
                String mod = extractModuleName(v);
                String pkg = extractPackageFqn(v);
                if (!mod.equalsIgnoreCase(filter) && !pkg.equalsIgnoreCase(filter)) continue;
            }
            String c = extractClassFqn(v);
            classMethodCounts.put(c, classMethodCounts.getOrDefault(c, 0) + 1);

            for (DefaultEdge e : g.outgoingEdgesOf(v)) {
                String tgt = g.getEdgeTarget(e);
                if (filter != null && !filter.isEmpty()) {
                    String tgtMod = extractModuleName(tgt);
                    String tgtPkg = extractPackageFqn(tgt);
                    if (!tgtMod.equalsIgnoreCase(filter) && !tgtPkg.equalsIgnoreCase(filter)) continue;
                }
                String tgtClass = extractClassFqn(tgt);
                if (!c.equals(tgtClass)) {
                    classCalls.computeIfAbsent(c, k -> new LinkedHashMap<>())
                              .merge(tgtClass, 1, Integer::sum);
                }
            }
        }

        List<GraphNode> allNodes = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : classMethodCounts.entrySet()) {
            String cFqn = entry.getKey();
            int dot = cFqn.lastIndexOf('.');
            String simpleName = (dot >= 0) ? cFqn.substring(dot + 1) : cFqn;
            allNodes.add(new GraphNode(cFqn, simpleName, "class", "CLASS"));
        }

        List<GraphEdge> edges = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> srcEntry : classCalls.entrySet()) {
            String src = srcEntry.getKey();
            for (Map.Entry<String, Integer> tgtEntry : srcEntry.getValue().entrySet()) {
                String tgt = tgtEntry.getKey();
                int count = tgtEntry.getValue();
                edges.add(new GraphEdge(src, tgt, count > 1 ? "CALLS (" + count + ")" : "CALLS"));
            }
        }

        return new GraphView("ARCHITECTURE", allNodes, edges);
    }

    public static String extractModuleName(String fqn) {
        if (fqn == null || fqn.isEmpty()) return "default";
        int paren = fqn.indexOf('(');
        String base = (paren > 0) ? fqn.substring(0, paren) : fqn;
        String[] parts = base.split("\\.");
        if (parts.length >= 3) {
            if (parts[0].equals("com") || parts[0].equals("org") || parts[0].equals("io") || parts[0].equals("net") || parts[0].equals("dev") || parts[0].equals("app")) {
                if (parts.length >= 5) {
                    return parts[3]; // e.g. com.tcs.bancs.BS.AccountService -> BS
                } else if (parts.length == 4) {
                    return (parts[3].matches("^[A-Z0-9_]+$") && !parts[2].matches("^[A-Z0-9_]+$")) ? parts[3] : parts[2];
                }
                return parts[2];
            }
            return parts[0];
        } else if (parts.length == 2) {
            return parts[0];
        }
        return "default";
    }

    private static String extractPackageFqn(String fqn) {
        int paren = fqn.indexOf('(');
        String base = (paren > 0) ? fqn.substring(0, paren) : fqn;
        int dot = base.lastIndexOf('.');
        if (dot < 0) return "(default)";
        String classOrPkg = base.substring(0, dot);
        int dot2 = classOrPkg.lastIndexOf('.');
        return (dot2 >= 0) ? classOrPkg.substring(0, dot2) : classOrPkg;
    }

    private String extractClassFqn(String methodFqn) {
        int paren = methodFqn.indexOf('(');
        String base = (paren > 0) ? methodFqn.substring(0, paren) : methodFqn;
        int dot = base.lastIndexOf('.');
        return (dot >= 0) ? base.substring(0, dot) : base;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<GraphNode> bfs(Graph<String, DefaultEdge> g, String start,
                                 int maxDepth, String role, boolean hideGetters) {
        if (!g.containsVertex(start)) return Collections.emptyList();

        List<GraphNode> result = new ArrayList<>();
        BreadthFirstIterator<String, DefaultEdge> it =
            new BreadthFirstIterator<>(g, start);

        while (it.hasNext()) {
            String v    = it.next();
            int    depth = it.getDepth(v);
            if (v.equals(start)) continue;        // skip root itself
            if (depth > maxDepth) break;
            if (hideGetters && isPojoOrAccessor(v)) continue;
            result.add(new GraphNode(v, label(v), role, "METHOD"));
        }
        return result;
    }

    private String resolve(String unresolved, Map<String, List<String>> byName) {
        String stripped = unresolved.substring(1); // remove "~"
        int dot = stripped.lastIndexOf('.');
        if (dot < 0) return null;
        String scopeHint  = stripped.substring(0, dot).toLowerCase();
        String methodName = stripped.substring(dot + 1);

        List<String> candidates = byName.getOrDefault(methodName, Collections.emptyList());
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        return candidates.stream()
            .filter(c -> c.toLowerCase().contains(scopeHint))
            .findFirst()
            .orElse(candidates.get(0));
    }

    private String simpleMethodName(String fqn) {
        int paren = fqn.indexOf('(');
        String base = (paren > 0) ? fqn.substring(0, paren) : fqn;
        int dot = base.lastIndexOf('.');
        return (dot >= 0) ? base.substring(dot + 1) : base;
    }

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
        public final String role;   // root | caller | callee | module | package | class
        public final String type;   // METHOD | FIELD | TYPE | MODULE | PACKAGE | CLASS

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

    // ─────────────────────────────────────────────────────────────────────────
    // DSM (Dependency Structure Matrix) view
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a Dependency Structure Matrix at Module, Package, or Class scope.
     */
    public DSMPayload dsmView(String scope, String filter) {
        Graph<String, DefaultEdge> g = callGraph;
        Set<String> allClasses = new TreeSet<>();
        for (String v : g.vertexSet()) allClasses.add(extractClassFqn(v));

        String effectiveScope = scope;
        if (effectiveScope == null || effectiveScope.isEmpty() || "auto".equalsIgnoreCase(effectiveScope)) {
            Set<String> modules = new HashSet<>();
            for (String v : g.vertexSet()) modules.add(extractModuleName(v));
            effectiveScope = (modules.size() > 1 && allClasses.size() > 300) ? "modules" : "classes";
        }

        if ("modules".equalsIgnoreCase(effectiveScope)) {
            DSMPayload modDsm = moduleDsmView();
            if (modDsm.classes.size() <= 1 && allClasses.size() > 1) {
                return classDsmView(filter);
            }
            return modDsm;
        } else if ("packages".equalsIgnoreCase(effectiveScope)) {
            DSMPayload pkgDsm = packageDsmView(filter);
            if (pkgDsm.classes.size() <= 1 && allClasses.size() > 1) {
                return classDsmView(filter);
            }
            return pkgDsm;
        } else {
            return classDsmView(filter);
        }
    }

    public DSMPayload dsmView() {
        return dsmView(null, null);
    }

    private DSMPayload moduleDsmView() {
        Graph<String, DefaultEdge> g = callGraph;
        Map<String, Map<String, Integer>> modCalls = new LinkedHashMap<>();
        Set<String> allModules = new TreeSet<>();

        for (String v : g.vertexSet()) {
            String m = extractModuleName(v);
            allModules.add(m);

            for (DefaultEdge e : g.outgoingEdgesOf(v)) {
                String tgt = g.getEdgeTarget(e);
                String tgtMod = extractModuleName(tgt);
                allModules.add(tgtMod);
                modCalls.computeIfAbsent(m, k -> new LinkedHashMap<>())
                        .merge(tgtMod, 1, Integer::sum);
            }
        }

        List<String> modList = new ArrayList<>(allModules);
        Map<String, Integer> indexMap = new HashMap<>();
        for (int i = 0; i < modList.size(); i++) indexMap.put(modList.get(i), i);

        int n = modList.size();
        int[][] matrix = new int[n][n];
        for (Map.Entry<String, Map<String, Integer>> srcEntry : modCalls.entrySet()) {
            Integer si = indexMap.get(srcEntry.getKey());
            if (si == null) continue;
            for (Map.Entry<String, Integer> tgtEntry : srcEntry.getValue().entrySet()) {
                Integer ti = indexMap.get(tgtEntry.getKey());
                if (ti == null) continue;
                matrix[si][ti] = tgtEntry.getValue();
            }
        }

        Map<String, String> groups = new LinkedHashMap<>();
        for (String m : modList) groups.put(m, "Modules");

        return new DSMPayload(modList, matrix, groups, "modules");
    }

    private DSMPayload packageDsmView(String moduleFilter) {
        Graph<String, DefaultEdge> g = callGraph;
        Map<String, Map<String, Integer>> pkgCalls = new LinkedHashMap<>();
        Set<String> allPkgs = new TreeSet<>();

        for (String v : g.vertexSet()) {
            if (moduleFilter != null && !moduleFilter.isEmpty() && !extractModuleName(v).equalsIgnoreCase(moduleFilter)) {
                continue;
            }
            String p = extractPackageFqn(v);
            allPkgs.add(p);

            for (DefaultEdge e : g.outgoingEdgesOf(v)) {
                String tgt = g.getEdgeTarget(e);
                if (moduleFilter != null && !moduleFilter.isEmpty() && !extractModuleName(tgt).equalsIgnoreCase(moduleFilter)) {
                    continue;
                }
                String tgtPkg = extractPackageFqn(tgt);
                allPkgs.add(tgtPkg);
                pkgCalls.computeIfAbsent(p, k -> new LinkedHashMap<>())
                        .merge(tgtPkg, 1, Integer::sum);
            }
        }

        List<String> pkgList = new ArrayList<>(allPkgs);
        Map<String, Integer> indexMap = new HashMap<>();
        for (int i = 0; i < pkgList.size(); i++) indexMap.put(pkgList.get(i), i);

        int n = pkgList.size();
        int[][] matrix = new int[n][n];
        for (Map.Entry<String, Map<String, Integer>> srcEntry : pkgCalls.entrySet()) {
            Integer si = indexMap.get(srcEntry.getKey());
            if (si == null) continue;
            for (Map.Entry<String, Integer> tgtEntry : srcEntry.getValue().entrySet()) {
                Integer ti = indexMap.get(tgtEntry.getKey());
                if (ti == null) continue;
                matrix[si][ti] = tgtEntry.getValue();
            }
        }

        Map<String, String> groups = new LinkedHashMap<>();
        for (String p : pkgList) groups.put(p, extractModuleName(p));

        return new DSMPayload(pkgList, matrix, groups, "packages");
    }

    private DSMPayload classDsmView(String filter) {
        Graph<String, DefaultEdge> g = callGraph;
        Map<String, Map<String, Integer>> classCalls = new LinkedHashMap<>();
        Set<String> allClasses = new TreeSet<>();

        for (String v : g.vertexSet()) {
            if (filter != null && !filter.isEmpty()) {
                String mod = extractModuleName(v);
                String pkg = extractPackageFqn(v);
                if (!mod.equalsIgnoreCase(filter) && !pkg.equalsIgnoreCase(filter)) continue;
            }
            String c = extractClassFqn(v);
            allClasses.add(c);

            for (DefaultEdge e : g.outgoingEdgesOf(v)) {
                String tgt = g.getEdgeTarget(e);
                if (filter != null && !filter.isEmpty()) {
                    String tgtMod = extractModuleName(tgt);
                    String tgtPkg = extractPackageFqn(tgt);
                    if (!tgtMod.equalsIgnoreCase(filter) && !tgtPkg.equalsIgnoreCase(filter)) continue;
                }
                String tgtClass = extractClassFqn(tgt);
                allClasses.add(tgtClass);
                classCalls.computeIfAbsent(c, k -> new LinkedHashMap<>())
                          .merge(tgtClass, 1, Integer::sum);
            }
        }

        List<String> classList = new ArrayList<>(allClasses);
        Map<String, Integer> indexMap = new HashMap<>();
        for (int i = 0; i < classList.size(); i++) indexMap.put(classList.get(i), i);

        int n = classList.size();
        int[][] matrix = new int[n][n];
        for (Map.Entry<String, Map<String, Integer>> srcEntry : classCalls.entrySet()) {
            Integer si = indexMap.get(srcEntry.getKey());
            if (si == null) continue;
            for (Map.Entry<String, Integer> tgtEntry : srcEntry.getValue().entrySet()) {
                Integer ti = indexMap.get(tgtEntry.getKey());
                if (ti == null) continue;
                matrix[si][ti] = tgtEntry.getValue();
            }
        }

        Map<String, String> classPackages = new LinkedHashMap<>();
        for (String c : classList) {
            int dot = c.lastIndexOf('.');
            classPackages.put(c, dot >= 0 ? c.substring(0, dot) : "(default)");
        }

        return new DSMPayload(classList, matrix, classPackages, "classes");
    }

    public static class DSMSparseCell {
        public final int r;
        public final int c;
        public final int v;

        public DSMSparseCell(int r, int c, int v) {
            this.r = r; this.c = c; this.v = v;
        }
    }

    /** DSM response payload. */
    public static class DSMPayload {
        public final List<String> classes;
        public final int[][]      matrix;
        public final Map<String, String> packages;
        public final String scope;
        public final List<DSMSparseCell> cells;

        public DSMPayload(List<String> classes, int[][] matrix, Map<String, String> packages, String scope) {
            this.classes = classes;
            this.matrix  = matrix;
            this.packages = packages;
            this.scope = scope;
            this.cells = new ArrayList<>();
            if (matrix != null) {
                for (int r = 0; r < matrix.length; r++) {
                    for (int c = 0; c < matrix[r].length; c++) {
                        if (matrix[r][c] > 0) {
                            this.cells.add(new DSMSparseCell(r, c, matrix[r][c]));
                        }
                    }
                }
            }
        }

        public DSMPayload(List<String> classes, int[][] matrix, Map<String, String> packages) {
            this(classes, matrix, packages, "classes");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Treemap (Hierarchical size/complexity) view
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a hierarchical treemap payload from indexed types and methods.
     * Hierarchy: root -> modules -> packages -> classes -> methods.
     */
    public static TreemapNode treemapView(List<TreemapTypeRecord> types,
                                          List<TreemapMethodRecord> methods,
                                          String scope,
                                          String filter) {
        Map<String, List<TreemapMethodRecord>> methodsByType = new LinkedHashMap<>();
        for (TreemapMethodRecord m : methods) {
            methodsByType.computeIfAbsent(m.declaringTypeFqn, k -> new ArrayList<>()).add(m);
        }

        // Group types by Module -> Package
        Map<String, Map<String, List<TreemapTypeRecord>>> typesByModulePkg = new LinkedHashMap<>();
        for (TreemapTypeRecord t : types) {
            String mod = extractModuleName(t.fqn);
            if (filter != null && !filter.isEmpty() && !mod.equalsIgnoreCase(filter) && !t.packageFqn.equalsIgnoreCase(filter)) {
                continue;
            }
            String pkg = t.packageFqn != null ? t.packageFqn : "(default)";
            typesByModulePkg.computeIfAbsent(mod, k -> new LinkedHashMap<>())
                            .computeIfAbsent(pkg, k -> new ArrayList<>())
                            .add(t);
        }

        TreemapNode root = new TreemapNode("root", 0, 0);
        boolean singleModule = typesByModulePkg.size() <= 1;

        for (Map.Entry<String, Map<String, List<TreemapTypeRecord>>> modEntry : typesByModulePkg.entrySet()) {
            String modName = modEntry.getKey();
            TreemapNode modNode = singleModule ? root : new TreemapNode(modName, 0, 0);
            modNode.kind = "MODULE";

            for (Map.Entry<String, List<TreemapTypeRecord>> pkgEntry : modEntry.getValue().entrySet()) {
                TreemapNode pkgNode = new TreemapNode(pkgEntry.getKey(), 0, 0);
                pkgNode.kind = "PACKAGE";

                for (TreemapTypeRecord t : pkgEntry.getValue()) {
                    int lineCount = Math.max(t.lineCount, 1);
                    TreemapNode classNode = new TreemapNode(t.simpleName, lineCount, 0);
                    classNode.fqn = t.fqn;
                    classNode.kind = t.kind;

                    List<TreemapMethodRecord> classMethods = methodsByType.get(t.fqn);
                    if (classMethods != null && !classMethods.isEmpty()) {
                        int maxComplexity = 0;
                        for (TreemapMethodRecord m : classMethods) {
                            int mLines = Math.max(m.endLine - m.startLine + 1, 1);
                            int mComplexity = Math.max(m.cyclomaticComplexity, 1);
                            TreemapNode methodNode = new TreemapNode(m.simpleName, mLines, mComplexity);
                            methodNode.fqn = m.fqn;
                            methodNode.kind = "METHOD";
                            classNode.children.add(methodNode);
                            maxComplexity = Math.max(maxComplexity, mComplexity);
                        }
                        classNode.complexity = maxComplexity;
                        int methodSum = classNode.children.stream().mapToInt(c -> c.size).sum();
                        if (methodSum > 0) classNode.size = methodSum;
                    }

                    pkgNode.children.add(classNode);
                    pkgNode.size += classNode.size;
                }

                if (pkgNode.children.isEmpty()) continue;
                modNode.children.add(pkgNode);
                modNode.size += pkgNode.size;
            }

            if (!singleModule && !modNode.children.isEmpty()) {
                root.children.add(modNode);
                root.size += modNode.size;
            }
        }

        if (singleModule) {
            root.size = root.children.stream().mapToInt(c -> c.size).sum();
        }

        return root;
    }

    public static TreemapNode treemapView(List<TreemapTypeRecord> types,
                                          List<TreemapMethodRecord> methods) {
        return treemapView(types, methods, null, null);
    }

    /** Lightweight record for type data passed into treemapView(). */
    public static class TreemapTypeRecord {
        public final String fqn;
        public final String simpleName;
        public final String packageFqn;
        public final String kind;
        public final int    lineCount;

        public TreemapTypeRecord(String fqn, String simpleName, String packageFqn, String kind, int lineCount) {
            this.fqn = fqn; this.simpleName = simpleName; this.packageFqn = packageFqn;
            this.kind = kind; this.lineCount = lineCount;
        }
    }

    /** Lightweight record for method data passed into treemapView(). */
    public static class TreemapMethodRecord {
        public final String fqn;
        public final String simpleName;
        public final String declaringTypeFqn;
        public final int    startLine;
        public final int    endLine;
        public final int    cyclomaticComplexity;

        public TreemapMethodRecord(String fqn, String simpleName, String declaringTypeFqn,
                                   int startLine, int endLine, int cyclomaticComplexity) {
            this.fqn = fqn; this.simpleName = simpleName; this.declaringTypeFqn = declaringTypeFqn;
            this.startLine = startLine; this.endLine = endLine; this.cyclomaticComplexity = cyclomaticComplexity;
        }
    }

    /** A node in the treemap hierarchy. */
    public static class TreemapNode {
        public String name;
        public int    size;
        public int    complexity;
        public String fqn;
        public String kind;
        public List<TreemapNode> children = new ArrayList<>();

        public TreemapNode(String name, int size, int complexity) {
            this.name = name;
            this.size = size;
            this.complexity = complexity;
        }
    }
}

