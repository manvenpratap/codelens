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

    /** Returns the underlying directed JGraphT call graph. */
    public synchronized Graph<String, DefaultEdge> getCallGraph() {
        return callGraph;
    }

    /** Returns the number of vertices in the call graph. */
    public synchronized int vertexCount() {
        return callGraph != null ? callGraph.vertexSet().size() : 0;
    }

    /** Returns the number of edges in the call graph. */
    public synchronized int edgeCount() {
        return callGraph != null ? callGraph.edgeSet().size() : 0;
    }


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

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String phase, int current, int total, String detail);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static String dedup(Map<String, String> pool, String s) {
        if (s == null) return null;
        String existing = pool.putIfAbsent(s, s);
        return existing != null ? existing : s;
    }

    /**
     * Rebuilds the call graph by streaming edges directly from a database cursor with optional progress updates.
     */
    public synchronized void rebuild(List<String> allMethodFqns, EdgeStreamer edgeStreamer, ProgressListener listener) throws Exception {
        Graph<String, DefaultEdge> g = new DefaultDirectedGraph<>(DefaultEdge.class);
        int totalMethods = allMethodFqns != null ? allMethodFqns.size() : 0;
        Map<String, List<String>> byName = new HashMap<>();
        Map<String, List<String>> byClassAndMethod = new HashMap<>();
        Map<String, List<String>> byClassFqnAndMethod = new HashMap<>();
        Map<String, List<String>> byPackageAndMethod = new HashMap<>();
        Map<String, String> dedupPool = new HashMap<>(Math.min(500_000, totalMethods));
        Map<String, String> resolveCache = new HashMap<>(Math.max(65536, totalMethods));

        // Populate vertex set with scoped deduplicated strings
        if (allMethodFqns != null) {
            int mCount = 0;
            for (String fqn : allMethodFqns) {
                String interned = dedup(dedupPool, fqn);
                g.addVertex(interned);
                String simpleName = dedup(dedupPool, simpleMethodName(interned));
                byName.computeIfAbsent(simpleName, k -> new ArrayList<>(2)).add(interned);

                String classFqn = extractClassFqn(interned);
                int lastDot = classFqn.lastIndexOf('.');
                String simpleClass = (lastDot >= 0) ? classFqn.substring(lastDot + 1) : classFqn;
                String classMethodKey = (simpleClass + "." + simpleName).toLowerCase();
                String fqnMethodKey = (classFqn + "." + simpleName).toLowerCase();

                byClassAndMethod.computeIfAbsent(classMethodKey, k -> new ArrayList<>(2)).add(interned);
                byClassFqnAndMethod.computeIfAbsent(fqnMethodKey, k -> new ArrayList<>(2)).add(interned);

                String pkg = extractPackageFqn(interned);
                if (pkg != null && !pkg.isEmpty() && !"(default)".equalsIgnoreCase(pkg)) {
                    String pkgMethodKey = (pkg + "." + simpleName).toLowerCase();
                    byPackageAndMethod.computeIfAbsent(pkgMethodKey, k -> new ArrayList<>(2)).add(interned);
                }

                mCount++;
                int mStride = Math.max(1000, totalMethods / 100);
                if (listener != null && (mCount % mStride == 0 || mCount == totalMethods)) {
                    listener.onProgress("Call Graph: Indexing Methods", mCount, totalMethods,
                        String.format("Indexed %,d / %,d method vertices (%s)", mCount, totalMethods, simpleName));
                }
            }
        }

        // Stream edges, resolving "~" prefixed targets with fast memoization
        if (edgeStreamer != null) {
            final int[] edgeCount = new int[]{0};
            final int eStride = 5000;
            final String[] lastFromHolder = new String[2]; // [0] = from, [1] = callerClass
            edgeStreamer.stream((rawFrom, rawTo) -> {
                if (rawFrom == null || rawTo == null) return;
                String from = dedup(dedupPool, rawFrom);
                String to   = rawTo;

                if (to.startsWith("~")) {
                    String callerClass;
                    if (from.equals(lastFromHolder[0])) {
                        callerClass = lastFromHolder[1];
                    } else {
                        callerClass = extractClassFqn(from);
                        lastFromHolder[0] = from;
                        lastFromHolder[1] = callerClass;
                    }
                    String cacheKey = callerClass + "|" + to;
                    String cached = resolveCache.get(cacheKey);
                    if (cached != null) {
                        to = cached.isEmpty() ? null : cached;
                    } else {
                        to = resolve(from, to, byName, byClassAndMethod, byClassFqnAndMethod, byPackageAndMethod);
                        resolveCache.put(cacheKey, to != null ? to : "");
                    }
                } else if (!to.contains("(") && !g.containsVertex(to)) {
                    // Same-class or direct call without parameter signature (e.g. this.Get(), Get())
                    int dot = to.lastIndexOf('.');
                    if (dot > 0) {
                        String classFqn = to.substring(0, dot);
                        String methodName = to.substring(dot + 1);
                        String fqnKey = (classFqn + "." + methodName).toLowerCase();
                        List<String> fqnMatches = byClassFqnAndMethod.get(fqnKey);
                        if (fqnMatches != null && !fqnMatches.isEmpty()) {
                            if (fqnMatches.size() == 1) {
                                to = fqnMatches.get(0);
                            } else {
                                String best = disambiguateByCaller(from, fqnMatches);
                                to = (best != null) ? best : fqnMatches.get(0);
                            }
                        }
                    }
                }
                if (to == null || to.startsWith("~")) return;
                to = dedup(dedupPool, to);

                g.addVertex(from);
                g.addVertex(to);

                try { g.addEdge(from, to); }
                catch (Exception ignored) { /* duplicate edge */ }

                edgeCount[0]++;
                if (listener != null && edgeCount[0] % eStride == 0) {
                    listener.onProgress("Call Graph: Mapping Edges", edgeCount[0], -1,
                        String.format("Mapped %,d call edges (%s → %s)", edgeCount[0], simpleMethodName(from), simpleMethodName(to)));
                }
            });
            if (listener != null) {
                listener.onProgress("Call Graph: Mapping Edges", edgeCount[0], edgeCount[0],
                    String.format("Mapped %,d call edges total", edgeCount[0]));
            }
        }

        // Immediately release large temporary indexing maps to reduce heap footprint
        byName.clear();
        byClassAndMethod.clear();
        byClassFqnAndMethod.clear();
        byPackageAndMethod.clear();
        dedupPool.clear();
        resolveCache.clear();

        this.callGraph = g;
        log.info("Call graph rebuilt: {} vertices, {} edges",
            g.vertexSet().size(), g.edgeSet().size());
    }

    public synchronized void rebuild(List<String> allMethodFqns, EdgeStreamer edgeStreamer) throws Exception {
        rebuild(allMethodFqns, edgeStreamer, null);
    }

    /**
     * Rebuilds the call graph from pre-fetched (from, to) String pairs with progress reporting.
     */
    public synchronized void rebuildWithPairs(List<String> allMethodFqns, List<String[]> callPairs, ProgressListener listener) throws Exception {
        int totalPairs = callPairs != null ? callPairs.size() : 0;
        rebuild(allMethodFqns, consumer -> {
            if (callPairs != null) {
                int pCount = 0;
                int pStride = Math.max(2500, totalPairs / 100);
                for (String[] pair : callPairs) {
                    consumer.accept(pair[0], pair[1]);
                    pCount++;
                    if (listener != null && (pCount % pStride == 0 || pCount == totalPairs)) {
                        listener.onProgress("Call Graph: Mapping Edges", pCount, totalPairs,
                            String.format("Mapped %,d / %,d call edges (%s → %s)",
                                pCount, totalPairs, simpleMethodName(pair[0]), simpleMethodName(pair[1])));
                    }
                }
            }
        }, listener);
    }

    /**
     * Rebuilds the call graph from pre-fetched (from, to) String pairs.
     */
    public synchronized void rebuildWithPairs(List<String> allMethodFqns, List<String[]> callPairs) throws Exception {
        rebuildWithPairs(allMethodFqns, callPairs, null);
    }

    /**
     * Returns the set of all calling method FQNs (vertices with out-degree > 0)
     * directly from the in-memory call graph without requiring a database query.
     */
    public synchronized Set<String> getCallingMethodFqns() {
        Set<String> callers = new HashSet<>();
        if (callGraph != null) {
            for (DefaultEdge e : callGraph.edgeSet()) {
                callers.add(callGraph.getEdgeSource(e));
            }
        }
        return callers;
    }

    /**
     * Rebuilds the call graph from scratch (in-memory list overload for tests / backwards compatibility).
     *
     * @param allMethodFqns    every method FQN discovered during the scan
     * @param callRelationships CALLS relationships (may include "~" prefixed targets)
     */
    public synchronized void rebuild(List<String> allMethodFqns,
                                     List<CodeRelationship> callRelationships,
                                     ProgressListener listener) {
        try {
            int totalRels = callRelationships != null ? callRelationships.size() : 0;
            rebuild(allMethodFqns, consumer -> {
                if (callRelationships != null) {
                    int rCount = 0;
                    for (CodeRelationship rel : callRelationships) {
                        if ("CALLS".equals(rel.getKind())) {
                            consumer.accept(rel.getFromEntityFqn(), rel.getToEntityFqn());
                        }
                        rCount++;
                        if (listener != null && (rCount % 2000 == 0 || rCount == totalRels)) {
                            listener.onProgress("Call Graph: Mapping Edges", rCount, totalRels,
                                String.format("Mapped %,d / %,d call edges", rCount, totalRels));
                        }
                    }
                }
            }, listener);
        } catch (Exception e) {
            log.error("Failed to rebuild call graph", e);
        }
    }

    public synchronized void rebuild(List<String> allMethodFqns,
                                     List<CodeRelationship> callRelationships) {
        rebuild(allMethodFqns, callRelationships, null);
    }


    private static volatile Set<String> customPojoExactNames = Collections.emptySet();
    private static volatile List<String> customPojoPrefixes = Collections.emptyList();
    private static volatile List<String> customPojoSuffixes = Collections.emptyList();

    /**
     * Configures custom POJO patterns dynamically (e.g. from codelens.conf or UI settings).
     */
    public static void setCustomPojoPatterns(String patternsCommaSeparated) {
        if (patternsCommaSeparated == null || patternsCommaSeparated.isBlank()) {
            customPojoExactNames = Collections.emptySet();
            customPojoPrefixes = Collections.emptyList();
            customPojoSuffixes = Collections.emptyList();
            return;
        }
        Set<String> exact = new HashSet<>();
        List<String> prefixes = new ArrayList<>();
        List<String> suffixes = new ArrayList<>();
        for (String p : patternsCommaSeparated.split("[,\\s]+")) {
            String trimmed = p.trim().toLowerCase();
            if (trimmed.isEmpty()) continue;
            if (trimmed.endsWith("*") && trimmed.length() > 1) {
                prefixes.add(trimmed.substring(0, trimmed.length() - 1));
            } else if (trimmed.startsWith("*") && trimmed.length() > 1) {
                suffixes.add(trimmed.substring(1));
            } else {
                exact.add(trimmed);
            }
        }
        customPojoExactNames = exact;
        customPojoPrefixes = prefixes;
        customPojoSuffixes = suffixes;
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

        // Never filter core persistent lifecycle methods
        if ("Get".equals(name) || "Create".equals(name) || "Modify".equals(name)
                || "SModify".equals(name) || "MModify".equals(name) || "MModidy".equals(name)) {
            return false;
        }

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

        // Custom configured POJO patterns
        String lowerName = name.toLowerCase();
        if (customPojoExactNames.contains(lowerName)) {
            return true;
        }
        for (String pfx : customPojoPrefixes) {
            if (lowerName.startsWith(pfx)) return true;
        }
        for (String sfx : customPojoSuffixes) {
            if (lowerName.endsWith(sfx)) return true;
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
    public synchronized int callerCount(String methodFqn) {
        if (methodFqn == null || callGraph == null) return 0;
        if (callGraph.containsVertex(methodFqn)) {
            return callGraph.inDegreeOf(methodFqn);
        }
        String alt = methodFqn.endsWith("()")
            ? methodFqn.substring(0, methodFqn.length() - 2)
            : methodFqn + "()";
        if (callGraph.containsVertex(alt)) {
            return callGraph.inDegreeOf(alt);
        }
        int count = 0;
        boolean foundAny = false;
        String prefix = methodFqn + "(";
        for (String v : callGraph.vertexSet()) {
            if (v.startsWith(prefix)) {
                count += callGraph.inDegreeOf(v);
                foundAny = true;
            }
        }
        return foundAny ? count : 0;
    }

    /**
     * Returns the number of direct callees (out-degree) for the given method.
     */
    public synchronized int calleeCount(String methodFqn) {
        if (methodFqn == null || callGraph == null) return 0;
        if (callGraph.containsVertex(methodFqn)) {
            return callGraph.outDegreeOf(methodFqn);
        }
        String alt = methodFqn.endsWith("()")
            ? methodFqn.substring(0, methodFqn.length() - 2)
            : methodFqn + "()";
        if (callGraph.containsVertex(alt)) {
            return callGraph.outDegreeOf(alt);
        }
        int count = 0;
        boolean foundAny = false;
        String prefix = methodFqn + "(";
        for (String v : callGraph.vertexSet()) {
            if (v.startsWith(prefix)) {
                count += callGraph.outDegreeOf(v);
                foundAny = true;
            }
        }
        return foundAny ? count : 0;
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
        String resolvedFqn = findVertex(rootFqn);
        if (resolvedFqn == null) resolvedFqn = rootFqn;
        List<GraphNode> calleeNodes = callees(resolvedFqn, depth, hideGetters);
        List<GraphNode> callerNodes = callers(resolvedFqn, depth, hideGetters);

        Set<String> seen = new HashSet<>();
        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> edges    = new ArrayList<>();

        // Root node
        GraphNode root = new GraphNode(resolvedFqn, label(resolvedFqn), "root", "METHOD");
        allNodes.add(root);
        seen.add(resolvedFqn);

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

        return computeHierarchyLayout(resolvedFqn, new GraphView(resolvedFqn, allNodes, edges));
    }

    public GraphView callersView(String rootFqn, int depth) {
        return callersView(rootFqn, depth, false);
    }

    public GraphView callersView(String rootFqn, int depth, boolean hideGetters) {
        String resolvedFqn = findVertex(rootFqn);
        if (resolvedFqn == null) resolvedFqn = rootFqn;
        List<GraphNode> callerNodes = callers(resolvedFqn, depth, hideGetters);

        Set<String> seen = new HashSet<>();
        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> edges    = new ArrayList<>();

        // Root node
        GraphNode root = new GraphNode(resolvedFqn, label(resolvedFqn), "root", "METHOD");
        allNodes.add(root);
        seen.add(resolvedFqn);

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

        return computeHierarchyLayout(resolvedFqn, new GraphView(resolvedFqn, allNodes, edges));
    }

    public GraphView calleesView(String rootFqn, int depth) {
        return calleesView(rootFqn, depth, false);
    }

    public GraphView calleesView(String rootFqn, int depth, boolean hideGetters) {
        String resolvedFqn = findVertex(rootFqn);
        if (resolvedFqn == null) resolvedFqn = rootFqn;
        List<GraphNode> calleeNodes = callees(resolvedFqn, depth, hideGetters);

        Set<String> seen = new HashSet<>();
        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> edges    = new ArrayList<>();

        // Root node
        GraphNode root = new GraphNode(resolvedFqn, label(resolvedFqn), "root", "METHOD");
        allNodes.add(root);
        seen.add(resolvedFqn);

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

        return computeHierarchyLayout(resolvedFqn, new GraphView(resolvedFqn, allNodes, edges));
    }

    /**
     * Finds a matching vertex in the call graph using exact, parentheses-variant, or prefix match.
     */
    public synchronized String findVertex(String fqn) {
        if (fqn == null || fqn.trim().isEmpty() || callGraph == null) return null;
        String trimmed = fqn.trim();
        if (callGraph.containsVertex(trimmed)) return trimmed;
        String alt = trimmed.endsWith("()") ? trimmed.substring(0, trimmed.length() - 2) : trimmed + "()";
        if (callGraph.containsVertex(alt)) return alt;

        String prefix = trimmed.contains("(") ? trimmed : trimmed + "(";
        for (String v : callGraph.vertexSet()) {
            if (v.startsWith(prefix) || v.equals(trimmed)) {
                return v;
            }
        }
        return null;
    }

    /**
     * Hub Explorer view: groups callers and callees by package with member details.
     * Provides aggregated data suitable for radial fan/arc rendering of high-connectivity hub nodes.
     *
     * @param fqn method or class FQN
     * @param direction "callers", "callees", or "both"
     */
    public synchronized HubExplorerView hubExplorerView(String fqn, String direction) {
        if (fqn == null || fqn.trim().isEmpty() || callGraph == null) {
            return new HubExplorerView();
        }

        String target = fqn.trim();
        String rootVertex = findVertex(target);
        if (rootVertex == null) {
            // Check if target is a class FQN
            return hubExplorerClassView(target, direction);
        }

        String pkg = extractPackageFqn(rootVertex);
        String cls = extractClassFqn(rootVertex);
        int totalCallers = callGraph.inDegreeOf(rootVertex);
        int totalCallees = callGraph.outDegreeOf(rootVertex);

        GraphNode centerNode = new GraphNode(rootVertex, label(rootVertex), "root", "METHOD");
        centerNode.packageFqn = pkg;
        centerNode.className = cls;
        centerNode.inDegree = totalCallers;
        centerNode.outDegree = totalCallees;

        boolean includeCallers = direction == null || "callers".equalsIgnoreCase(direction) || "both".equalsIgnoreCase(direction);
        boolean includeCallees = direction == null || "callees".equalsIgnoreCase(direction) || "both".equalsIgnoreCase(direction);

        List<HubExplorerGroup> callerGroups = new ArrayList<>();
        List<HubExplorerGroup> calleeGroups = new ArrayList<>();

        if (includeCallers) {
            Map<String, List<GraphNode>> byPackage = new HashMap<>();
            for (DefaultEdge e : callGraph.incomingEdgesOf(rootVertex)) {
                String callerFqn = callGraph.getEdgeSource(e);
                GraphNode node = new GraphNode(callerFqn, label(callerFqn), "caller", "METHOD");
                node.packageFqn = extractPackageFqn(callerFqn);
                node.className = extractClassFqn(callerFqn);
                node.inDegree = callGraph.inDegreeOf(callerFqn);
                node.outDegree = callGraph.outDegreeOf(callerFqn);
                byPackage.computeIfAbsent(node.packageFqn, k -> new ArrayList<>()).add(node);
            }

            for (Map.Entry<String, List<GraphNode>> entry : byPackage.entrySet()) {
                String pkgFqn = entry.getKey();
                List<GraphNode> members = entry.getValue();
                members.sort(Comparator.comparing((GraphNode n) -> n.className != null ? n.className : "")
                    .thenComparing(n -> n.label != null ? n.label : ""));
                String shortName = extractModuleName(pkgFqn);
                if (shortName == null || shortName.isEmpty() || "default".equals(shortName)) {
                    shortName = pkgFqn.contains(".") ? pkgFqn.substring(pkgFqn.lastIndexOf('.') + 1) : pkgFqn;
                }
                callerGroups.add(new HubExplorerGroup(pkgFqn, shortName, members.size(), members));
            }
            callerGroups.sort((a, b) -> Integer.compare(b.count, a.count));
        }

        if (includeCallees) {
            Map<String, List<GraphNode>> byPackage = new HashMap<>();
            for (DefaultEdge e : callGraph.outgoingEdgesOf(rootVertex)) {
                String calleeFqn = callGraph.getEdgeTarget(e);
                GraphNode node = new GraphNode(calleeFqn, label(calleeFqn), "callee", "METHOD");
                node.packageFqn = extractPackageFqn(calleeFqn);
                node.className = extractClassFqn(calleeFqn);
                node.inDegree = callGraph.inDegreeOf(calleeFqn);
                node.outDegree = callGraph.outDegreeOf(calleeFqn);
                byPackage.computeIfAbsent(node.packageFqn, k -> new ArrayList<>()).add(node);
            }

            for (Map.Entry<String, List<GraphNode>> entry : byPackage.entrySet()) {
                String pkgFqn = entry.getKey();
                List<GraphNode> members = entry.getValue();
                members.sort(Comparator.comparing((GraphNode n) -> n.className != null ? n.className : "")
                    .thenComparing(n -> n.label != null ? n.label : ""));
                String shortName = extractModuleName(pkgFqn);
                if (shortName == null || shortName.isEmpty() || "default".equals(shortName)) {
                    shortName = pkgFqn.contains(".") ? pkgFqn.substring(pkgFqn.lastIndexOf('.') + 1) : pkgFqn;
                }
                calleeGroups.add(new HubExplorerGroup(pkgFqn, shortName, members.size(), members));
            }
            calleeGroups.sort((a, b) -> Integer.compare(b.count, a.count));
        }

        return new HubExplorerView(centerNode, totalCallers, totalCallees, callerGroups, calleeGroups);
    }

    /**
     * Hub Explorer view for class-level aggregation: aggregates callers and callees across all methods of the class.
     */
    private synchronized HubExplorerView hubExplorerClassView(String classFqn, String direction) {
        List<String> classMethods = new ArrayList<>();
        for (String v : callGraph.vertexSet()) {
            if (classFqn.equals(extractClassFqn(v))) {
                classMethods.add(v);
            }
        }

        if (classMethods.isEmpty()) {
            GraphNode emptyNode = new GraphNode(classFqn, classFqn.contains(".") ? classFqn.substring(classFqn.lastIndexOf('.') + 1) : classFqn, "root", "CLASS");
            return new HubExplorerView(emptyNode, 0, 0, Collections.emptyList(), Collections.emptyList());
        }

        String simpleName = classFqn.contains(".") ? classFqn.substring(classFqn.lastIndexOf('.') + 1) : classFqn;
        String pkg = extractPackageFqn(classFqn);

        Set<String> classMethodSet = new HashSet<>(classMethods);
        Set<String> callerSet = new HashSet<>();
        Set<String> calleeSet = new HashSet<>();

        for (String m : classMethods) {
            for (DefaultEdge e : callGraph.incomingEdgesOf(m)) {
                String src = callGraph.getEdgeSource(e);
                if (!classMethodSet.contains(src)) {
                    callerSet.add(src);
                }
            }
            for (DefaultEdge e : callGraph.outgoingEdgesOf(m)) {
                String tgt = callGraph.getEdgeTarget(e);
                if (!classMethodSet.contains(tgt)) {
                    calleeSet.add(tgt);
                }
            }
        }

        GraphNode centerNode = new GraphNode(classFqn, simpleName, "root", "CLASS");
        centerNode.packageFqn = pkg;
        centerNode.className = classFqn;
        centerNode.inDegree = callerSet.size();
        centerNode.outDegree = calleeSet.size();

        boolean includeCallers = direction == null || "callers".equalsIgnoreCase(direction) || "both".equalsIgnoreCase(direction);
        boolean includeCallees = direction == null || "callees".equalsIgnoreCase(direction) || "both".equalsIgnoreCase(direction);

        List<HubExplorerGroup> callerGroups = new ArrayList<>();
        List<HubExplorerGroup> calleeGroups = new ArrayList<>();

        if (includeCallers) {
            Map<String, List<GraphNode>> byPackage = new HashMap<>();
            for (String callerFqn : callerSet) {
                GraphNode node = new GraphNode(callerFqn, label(callerFqn), "caller", "METHOD");
                node.packageFqn = extractPackageFqn(callerFqn);
                node.className = extractClassFqn(callerFqn);
                node.inDegree = callGraph.inDegreeOf(callerFqn);
                node.outDegree = callGraph.outDegreeOf(callerFqn);
                byPackage.computeIfAbsent(node.packageFqn, k -> new ArrayList<>()).add(node);
            }

            for (Map.Entry<String, List<GraphNode>> entry : byPackage.entrySet()) {
                String pkgFqn = entry.getKey();
                List<GraphNode> members = entry.getValue();
                members.sort(Comparator.comparing((GraphNode n) -> n.className != null ? n.className : "")
                    .thenComparing(n -> n.label != null ? n.label : ""));
                String shortName = extractModuleName(pkgFqn);
                if (shortName == null || shortName.isEmpty() || "default".equals(shortName)) {
                    shortName = pkgFqn.contains(".") ? pkgFqn.substring(pkgFqn.lastIndexOf('.') + 1) : pkgFqn;
                }
                callerGroups.add(new HubExplorerGroup(pkgFqn, shortName, members.size(), members));
            }
            callerGroups.sort((a, b) -> Integer.compare(b.count, a.count));
        }

        if (includeCallees) {
            Map<String, List<GraphNode>> byPackage = new HashMap<>();
            for (String calleeFqn : calleeSet) {
                GraphNode node = new GraphNode(calleeFqn, label(calleeFqn), "callee", "METHOD");
                node.packageFqn = extractPackageFqn(calleeFqn);
                node.className = extractClassFqn(calleeFqn);
                node.inDegree = callGraph.inDegreeOf(calleeFqn);
                node.outDegree = callGraph.outDegreeOf(calleeFqn);
                byPackage.computeIfAbsent(node.packageFqn, k -> new ArrayList<>()).add(node);
            }

            for (Map.Entry<String, List<GraphNode>> entry : byPackage.entrySet()) {
                String pkgFqn = entry.getKey();
                List<GraphNode> members = entry.getValue();
                members.sort(Comparator.comparing((GraphNode n) -> n.className != null ? n.className : "")
                    .thenComparing(n -> n.label != null ? n.label : ""));
                String shortName = extractModuleName(pkgFqn);
                if (shortName == null || shortName.isEmpty() || "default".equals(shortName)) {
                    shortName = pkgFqn.contains(".") ? pkgFqn.substring(pkgFqn.lastIndexOf('.') + 1) : pkgFqn;
                }
                calleeGroups.add(new HubExplorerGroup(pkgFqn, shortName, members.size(), members));
            }
            calleeGroups.sort((a, b) -> Integer.compare(b.count, a.count));
        }

        return new HubExplorerView(centerNode, callerSet.size(), calleeSet.size(), callerGroups, calleeGroups);
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

    public GraphView precomputedArchitectureGraphView(String scope, String filter) {
        return precomputedArchitectureGraphView(scope, filter, null);
    }

    public GraphView precomputedArchitectureGraphView(String scope, String filter, ProgressListener listener) {
        return computePrecomputedLayout(architectureGraphView(scope, filter), listener);
    }

    public GraphView precomputedFullGraphView(boolean hideGetters) {
        return precomputedFullGraphView(hideGetters, null);
    }

    public GraphView precomputedFullGraphView(boolean hideGetters, ProgressListener listener) {
        return computePrecomputedLayout(fullGraphView(hideGetters), listener);
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
                    // e.g. com.tcs.bancs.common -> common
                    // e.g. com.tcs.bancs.AM -> AM
                    // e.g. com.example.trading.Trade -> trading (Trade is a Class, not a module)
                    // If parts[3] is a Class Name (starts with uppercase, and has lowercase like "Trade"), module is parts[2].
                    // Otherwise if parts[3] is an all-caps module code ("AM") or lowercase package name ("common", "accounting"), module is parts[3].
                    if (Character.isUpperCase(parts[3].charAt(0)) && !parts[3].matches("^[A-Z0-9_]+$")) {
                        return parts[2];
                    }
                    return parts[3];
                }
                return parts[2];
            }
            return parts[0];
        } else if (parts.length == 2) {
            return parts[0];
        }
        return "default";
    }

    public static String extractPackageFqn(String fqn) {
        if (fqn == null || fqn.isEmpty()) return "(default)";
        int paren = fqn.indexOf('(');
        String base = (paren > 0) ? fqn.substring(0, paren) : fqn;
        int dot = base.lastIndexOf('.');
        if (dot < 0) return "(default)";
        String lastSegment = base.substring(dot + 1);

        // If there are no parentheses and the last segment starts with an uppercase letter,
        // this is a Class FQN (e.g. com.tcs.bancs.PM.PaymentStatus).
        if (paren < 0 && !lastSegment.isEmpty() && Character.isUpperCase(lastSegment.charAt(0))) {
            String pkg = base.substring(0, dot);
            int prevDot = pkg.lastIndexOf('.');
            if (prevDot >= 0) {
                String prevSeg = pkg.substring(prevDot + 1);
                // Handle nested class: com.foo.Bar.Inner -> com.foo
                if (!prevSeg.isEmpty() && Character.isUpperCase(prevSeg.charAt(0)) && prevSeg.length() > 2) {
                    return pkg.substring(0, prevDot);
                }
            }
            return pkg;
        }

        // Otherwise it is a Method/Field FQN (e.g. com.tcs.bancs.PM.PaymentStatus.getStatus()).
        String classFqn = base.substring(0, dot);
        int dot2 = classFqn.lastIndexOf('.');
        if (dot2 >= 0) {
            String prevClass = classFqn.substring(0, dot2);
            int dot3 = prevClass.lastIndexOf('.');
            String prevSeg = (dot3 >= 0) ? prevClass.substring(dot3 + 1) : prevClass;
            // Handle inner class method: com.foo.Bar.Inner.method() -> com.foo
            if (!prevSeg.isEmpty() && Character.isUpperCase(prevSeg.charAt(0)) && prevSeg.length() > 2) {
                return (dot3 >= 0) ? prevClass.substring(0, dot3) : prevClass;
            }
            return classFqn.substring(0, dot2);
        }
        return classFqn;
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
            GraphNode node = new GraphNode(v, label(v), role, "METHOD");
            node.depth = depth;
            result.add(node);
        }
        return result;
    }

    private String resolve(String from,
                           String unresolved,
                           Map<String, List<String>> byName,
                           Map<String, List<String>> byClassAndMethod,
                           Map<String, List<String>> byClassFqnAndMethod,
                           Map<String, List<String>> byPackageAndMethod) {
        String stripped = unresolved.substring(1); // remove "~"
        int dot = stripped.lastIndexOf('.');
        if (dot < 0) return null;
        String scopeHint  = stripped.substring(0, dot).toLowerCase();
        String methodName = stripped.substring(dot + 1);
        String methodKey  = methodName.toLowerCase();

        // 1. Direct match on Class FQN + method name (e.g. "com.tcs.bancs.tr.tradeexecution.get")
        List<String> fqnMatches = byClassFqnAndMethod.get(scopeHint + "." + methodKey);
        if (fqnMatches != null && !fqnMatches.isEmpty()) {
            if (fqnMatches.size() == 1) return fqnMatches.get(0);
            String best = disambiguateByCaller(from, fqnMatches);
            if (best != null) return best;
            return fqnMatches.get(0);
        }

        // 2. Direct match on Simple Class Name + method name (e.g. "tradeexecution.get")
        String scopeSimpleClass = scopeHint.contains(".") ? scopeHint.substring(scopeHint.lastIndexOf('.') + 1) : scopeHint;
        List<String> classMatches = byClassAndMethod.get(scopeSimpleClass + "." + methodKey);
        if (classMatches != null && !classMatches.isEmpty()) {
            if (classMatches.size() == 1) return classMatches.get(0);
            String best = disambiguateByCaller(from, classMatches);
            if (best != null) return best;
            return classMatches.get(0);
        }

        // 3. Fallback to candidate methods matching methodName
        List<String> candidates = byName.getOrDefault(methodName, Collections.emptyList());
        if (candidates.isEmpty()) return null;

        // 3a. If scopeHint is sufficiently specific and candidate pool is bounded, check class name affinity
        if (candidates.size() <= 40 && scopeHint.length() >= 3) {
            List<String> scopeMatches = new ArrayList<>(2);
            for (String c : candidates) {
                String cClass = extractClassFqn(c).toLowerCase();
                if (cClass.contains(scopeHint) || scopeHint.contains(cClass)) {
                    scopeMatches.add(c);
                }
            }
            if (!scopeMatches.isEmpty()) {
                if (scopeMatches.size() == 1) return scopeMatches.get(0);
                String best = disambiguateByCaller(from, scopeMatches);
                if (best != null) return best;
                return scopeMatches.get(0);
            }
        }

        // 4. Check caller package proximity via indexed lookup (O(1) direct lookup)
        if (from != null && !from.isEmpty()) {
            String callerPkg = extractPackageFqn(from);
            if (callerPkg != null && !callerPkg.isEmpty() && !"(default)".equalsIgnoreCase(callerPkg)) {
                List<String> samePkgCandidates = byPackageAndMethod.get(callerPkg.toLowerCase() + "." + methodKey);
                if (samePkgCandidates != null && !samePkgCandidates.isEmpty()) {
                    if (samePkgCandidates.size() == 1) {
                        return samePkgCandidates.get(0);
                    }
                    if (samePkgCandidates.size() > 1) {
                        String best = disambiguateByCaller(from, samePkgCandidates);
                        if (best != null) return best;
                        return samePkgCandidates.get(0);
                    }
                }
            }

            // 5. Check caller module proximity for bounded candidate pools
            if (candidates.size() <= 60) {
                String callerMod = extractModuleName(from);
                if (callerMod != null && !callerMod.isEmpty() && !"default".equalsIgnoreCase(callerMod)) {
                    List<String> sameModCandidates = new ArrayList<>();
                    for (String c : candidates) {
                        if (callerMod.equalsIgnoreCase(extractModuleName(c))) {
                            sameModCandidates.add(c);
                        }
                    }
                    if (sameModCandidates.size() == 1) {
                        return sameModCandidates.get(0);
                    }
                }
            }
        }

        // 6. If there is globally only ONE method in the entire codebase with this name, resolve to it.
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        return null;
    }

    private static String disambiguateByCaller(String from, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        if (from == null || from.isEmpty()) return candidates.get(0);

        String callerPkg = extractPackageFqn(from);
        if (callerPkg != null && !callerPkg.isEmpty()) {
            for (String c : candidates) {
                if (callerPkg.equalsIgnoreCase(extractPackageFqn(c))) {
                    return c;
                }
            }
        }

        String callerMod = extractModuleName(from);
        if (callerMod != null && !callerMod.isEmpty() && !"default".equalsIgnoreCase(callerMod)) {
            for (String c : candidates) {
                if (callerMod.equalsIgnoreCase(extractModuleName(c))) {
                    return c;
                }
            }
        }

        return null;
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

    /**
     * Pre-computes 2D coordinates (x, y) for all nodes in the GraphView
     * using a deterministic sunflower spiral layout clustered by package/module.
     * This eliminates the need for expensive client-side physics simulation.
     */
    public GraphView computePrecomputedLayout(GraphView view) {
        return computePrecomputedLayout(view, null);
    }

    public GraphView computePrecomputedLayout(GraphView view, ProgressListener listener) {
        if (view == null || view.nodes == null || view.nodes.isEmpty()) return view;

        Map<String, List<GraphNode>> groups = new LinkedHashMap<>();
        Map<String, Integer> degrees = new HashMap<>();

        for (GraphEdge e : view.edges) {
            degrees.merge(e.source, 1, Integer::sum);
            degrees.merge(e.target, 1, Integer::sum);
        }

        for (GraphNode n : view.nodes) {
            String grp = extractPackageFqn(n.id);
            if (grp == null || grp.isEmpty() || grp.equals("default") || grp.equals("(default)")) {
                grp = extractModuleName(n.id);
            }
            groups.computeIfAbsent(grp, k -> new ArrayList<>()).add(n);
        }

        List<String> groupKeys = new ArrayList<>(groups.keySet());
        groupKeys.sort((a, b) -> Integer.compare(groups.get(b).size(), groups.get(a).size()));
        int totalGroups = groupKeys.size();
        double groupSpread = Math.max(400.0, Math.sqrt(view.nodes.size()) * 52.0 + totalGroups * 36.0);
        double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0)); // ~137.5 degrees

        int placedNodes = 0;
        for (int gIdx = 0; gIdx < totalGroups; gIdx++) {
            String grp = groupKeys.get(gIdx);
            List<GraphNode> groupNodes = groups.get(grp);

            groupNodes.sort((a, b) -> Integer.compare(degrees.getOrDefault(b.id, 0), degrees.getOrDefault(a.id, 0)));

            double groupAngle = totalGroups == 1 ? 0.0 : ((2.0 * Math.PI * gIdx) / totalGroups + (gIdx % 2 != 0 ? 0.15 : -0.15));
            double groupDist = totalGroups == 1 ? 0.0 : (groupSpread * 0.55 + (gIdx % 3) * 35.0);
            double gcx = Math.cos(groupAngle) * groupDist;
            double gcy = Math.sin(groupAngle) * groupDist;

            GraphNode core = groupNodes.get(0);
            core.x = Math.round(gcx * 10.0) / 10.0;
            core.y = Math.round(gcy * 10.0) / 10.0;
            core.packageFqn = grp;

            for (int k = 1; k < groupNodes.size(); k++) {
                GraphNode nd = groupNodes.get(k);
                double ringAngle = groupAngle + k * goldenAngle;
                double ringDist = 38.0 + Math.sqrt(k) * 42.0;
                nd.x = Math.round((gcx + Math.cos(ringAngle) * ringDist) * 10.0) / 10.0;
                nd.y = Math.round((gcy + Math.sin(ringAngle) * ringDist) * 10.0) / 10.0;
                nd.packageFqn = grp;
            }
            placedNodes += groupNodes.size();

            int gStride = Math.max(10, totalGroups / 80);
            if (listener != null && ((gIdx + 1) % gStride == 0 || gIdx + 1 == totalGroups)) {
                listener.onProgress("Layout: Positioning Clusters", gIdx + 1, totalGroups,
                    String.format("Cluster [%d/%d] %s (%d nodes) placed · %,d/%,d nodes",
                        gIdx + 1, totalGroups, grp, groupNodes.size(), placedNodes, view.nodes.size()));
            }
        }

        return view;
    }

    /**
     * Pre-computes 2D coordinates (x, y) for a call hierarchy GraphView.
     * Places the root method at the center (0, 0), upstream callers to the left (x < 0),
     * and downstream callees to the right (x > 0), distributed cleanly by depth.
     */
    public GraphView computeHierarchyLayout(String rootFqn, GraphView view) {
        if (view == null || view.nodes == null || view.nodes.isEmpty()) return view;

        GraphNode rootNode = null;
        Map<Integer, List<GraphNode>> callersByDepth = new TreeMap<>();
        Map<Integer, List<GraphNode>> calleesByDepth = new TreeMap<>();
        List<GraphNode> others = new ArrayList<>();

        for (GraphNode node : view.nodes) {
            if ("root".equals(node.role) || (rootFqn != null && rootFqn.equals(node.id))) {
                rootNode = node;
                node.depth = 0;
            } else if ("caller".equals(node.role)) {
                int d = (node.depth != null && node.depth > 0) ? node.depth : 1;
                callersByDepth.computeIfAbsent(d, k -> new ArrayList<>()).add(node);
            } else if ("callee".equals(node.role)) {
                int d = (node.depth != null && node.depth > 0) ? node.depth : 1;
                calleesByDepth.computeIfAbsent(d, k -> new ArrayList<>()).add(node);
            } else {
                others.add(node);
            }
        }

        if (rootNode != null) {
            rootNode.x = 0.0;
            rootNode.y = 0.0;
            rootNode.packageFqn = extractPackageFqn(rootNode.id);
        }

        // Layout callers: flow leftwards (x < 0)
        double currentCallerBaseX = -240.0;
        for (Map.Entry<Integer, List<GraphNode>> entry : callersByDepth.entrySet()) {
            List<GraphNode> layerNodes = entry.getValue();
            int count = layerNodes.size();

            // Sort nodes by package so callers from the same package cluster together
            layerNodes.sort(Comparator.comparing((GraphNode n) -> {
                String p = extractPackageFqn(n.id);
                return p != null ? p : "";
            }).thenComparing(n -> n.id));

            if (count <= 25) {
                double baseX = currentCallerBaseX;
                double stepY = count > 12 ? 42.0 : 64.0;
                double startY = - ((count - 1) * stepY) / 2.0;

                for (int i = 0; i < count; i++) {
                    GraphNode n = layerNodes.get(i);
                    double staggerX = (count > 10) ? ((i % 2 == 0) ? -20.0 : 20.0) : 0.0;
                    n.x = Math.round((baseX + staggerX) * 10.0) / 10.0;
                    n.y = Math.round((startY + i * stepY) * 10.0) / 10.0;
                    n.packageFqn = extractPackageFqn(n.id);
                }
                currentCallerBaseX -= 240.0;
            } else {
                // Multi-column grid/fan layout for large layers to avoid 20,000px vertical smears
                int colCount = Math.min(12, Math.max(2, (int) Math.ceil(Math.sqrt(count * 0.8))));
                int rowsPerCol = (int) Math.ceil((double) count / colCount);
                double colSpacing = 160.0;
                double stepY = Math.max(28.0, Math.min(42.0, 900.0 / Math.max(1, rowsPerCol)));
                double startY = - ((rowsPerCol - 1) * stepY) / 2.0;

                for (int i = 0; i < count; i++) {
                    GraphNode n = layerNodes.get(i);
                    int col = i / rowsPerCol;
                    int row = i % rowsPerCol;

                    double colX = currentCallerBaseX - (col * colSpacing);
                    double archY = Math.sin((double) row / Math.max(1, rowsPerCol - 1) * Math.PI) * (col * 8.0);

                    n.x = Math.round(colX * 10.0) / 10.0;
                    n.y = Math.round((startY + row * stepY + (col % 2 == 0 ? 0 : stepY * 0.5) - archY) * 10.0) / 10.0;
                    n.packageFqn = extractPackageFqn(n.id);
                }
                currentCallerBaseX -= (colCount * colSpacing + 100.0);
            }
        }

        // Layout callees: flow rightwards (x > 0)
        double currentCalleeBaseX = +240.0;
        for (Map.Entry<Integer, List<GraphNode>> entry : calleesByDepth.entrySet()) {
            List<GraphNode> layerNodes = entry.getValue();
            int count = layerNodes.size();

            // Sort nodes by package so callees from the same package cluster together
            layerNodes.sort(Comparator.comparing((GraphNode n) -> {
                String p = extractPackageFqn(n.id);
                return p != null ? p : "";
            }).thenComparing(n -> n.id));

            if (count <= 25) {
                double baseX = currentCalleeBaseX;
                double stepY = count > 12 ? 42.0 : 64.0;
                double startY = - ((count - 1) * stepY) / 2.0;

                for (int i = 0; i < count; i++) {
                    GraphNode n = layerNodes.get(i);
                    double staggerX = (count > 10) ? ((i % 2 == 0) ? 20.0 : -20.0) : 0.0;
                    n.x = Math.round((baseX + staggerX) * 10.0) / 10.0;
                    n.y = Math.round((startY + i * stepY) * 10.0) / 10.0;
                    n.packageFqn = extractPackageFqn(n.id);
                }
                currentCalleeBaseX += 240.0;
            } else {
                int colCount = Math.min(12, Math.max(2, (int) Math.ceil(Math.sqrt(count * 0.8))));
                int rowsPerCol = (int) Math.ceil((double) count / colCount);
                double colSpacing = 160.0;
                double stepY = Math.max(28.0, Math.min(42.0, 900.0 / Math.max(1, rowsPerCol)));
                double startY = - ((rowsPerCol - 1) * stepY) / 2.0;

                for (int i = 0; i < count; i++) {
                    GraphNode n = layerNodes.get(i);
                    int col = i / rowsPerCol;
                    int row = i % rowsPerCol;

                    double colX = currentCalleeBaseX + (col * colSpacing);
                    double archY = Math.sin((double) row / Math.max(1, rowsPerCol - 1) * Math.PI) * (col * 8.0);

                    n.x = Math.round(colX * 10.0) / 10.0;
                    n.y = Math.round((startY + row * stepY + (col % 2 == 0 ? 0 : stepY * 0.5) - archY) * 10.0) / 10.0;
                    n.packageFqn = extractPackageFqn(n.id);
                }
                currentCalleeBaseX += (colCount * colSpacing + 100.0);
            }
        }

        // Any leftover nodes (e.g. general relationships): position below root
        if (!others.isEmpty()) {
            int count = others.size();
            double stepX = 140.0;
            double startX = - ((count - 1) * stepX) / 2.0;
            double posY = 200.0;
            for (int i = 0; i < count; i++) {
                GraphNode n = others.get(i);
                n.x = Math.round((startX + i * stepX) * 10.0) / 10.0;
                n.y = posY;
                n.packageFqn = extractPackageFqn(n.id);
            }
        }

        return view;
    }

    // ── Value objects ─────────────────────────────────────────────────────────

    /** A node in the rendered graph. */
    public static class GraphNode {
        public String id;
        public String label;
        public String role;   // root | caller | callee | module | package | class
        public String type;   // METHOD | FIELD | TYPE | MODULE | PACKAGE | CLASS
        public Double x;
        public Double y;
        public String packageFqn;
        public String className;
        public Integer depth;
        public Integer inDegree;
        public Integer outDegree;

        public GraphNode() {}

        public GraphNode(String id, String label, String role, String type) {
            this(id, label, role, type, null, null);
        }

        public GraphNode(String id, String label, String role, String type, Double x, Double y) {
            this.id = id; this.label = label; this.role = role; this.type = type;
            this.x = x; this.y = y;
        }
    }

    /** A directed edge in the rendered graph. */
    public static class GraphEdge {
        public String source;
        public String target;
        public String kind;

        public GraphEdge() {}

        public GraphEdge(String source, String target, String kind) {
            this.source = source; this.target = target; this.kind = kind;
        }
    }

    /** Full graph payload sent to the frontend. */
    public static class GraphView {
        public String           rootId;
        public List<GraphNode>  nodes;
        public List<GraphEdge>  edges;

        public GraphView() {}

        public GraphView(String rootId, List<GraphNode> nodes, List<GraphEdge> edges) {
            this.rootId = rootId; this.nodes = nodes; this.edges = edges;
        }
    }

    /** Group of caller/callee methods belonging to the same package for Hub Explorer. */
    public static class HubExplorerGroup {
        public String packageFqn;
        public String shortName;
        public int count;
        public List<GraphNode> members;

        public HubExplorerGroup() {}
        public HubExplorerGroup(String packageFqn, String shortName, int count, List<GraphNode> members) {
            this.packageFqn = packageFqn;
            this.shortName = shortName;
            this.count = count;
            this.members = members;
        }
    }

    /** Structured response for Hub Explorer view. */
    public static class HubExplorerView {
        public GraphNode centerNode;
        public int totalCallers;
        public int totalCallees;
        public List<HubExplorerGroup> callerGroups;
        public List<HubExplorerGroup> calleeGroups;

        public HubExplorerView() {}
        public HubExplorerView(GraphNode centerNode, int totalCallers, int totalCallees,
                               List<HubExplorerGroup> callerGroups, List<HubExplorerGroup> calleeGroups) {
            this.centerNode = centerNode;
            this.totalCallers = totalCallers;
            this.totalCallees = totalCallees;
            this.callerGroups = callerGroups;
            this.calleeGroups = calleeGroups;
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
        } else if ("methods".equalsIgnoreCase(effectiveScope) || "method".equalsIgnoreCase(effectiveScope)) {
            return methodDsmView(filter);
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

    private DSMPayload methodDsmView(String filter) {
        Graph<String, DefaultEdge> g = callGraph;
        Map<String, Map<String, Integer>> methodCalls = new LinkedHashMap<>();
        Set<String> allMethods = new TreeSet<>();

        for (String v : g.vertexSet()) {
            if (filter != null && !filter.isEmpty()) {
                String mod = extractModuleName(v);
                String pkg = extractPackageFqn(v);
                String cls = extractClassFqn(v);
                if (!mod.equalsIgnoreCase(filter) && !pkg.equalsIgnoreCase(filter) && !cls.equalsIgnoreCase(filter) && !v.contains(filter)) {
                    continue;
                }
            }
            allMethods.add(v);

            for (DefaultEdge e : g.outgoingEdgesOf(v)) {
                String tgt = g.getEdgeTarget(e);
                if (filter != null && !filter.isEmpty()) {
                    String tgtMod = extractModuleName(tgt);
                    String tgtPkg = extractPackageFqn(tgt);
                    String tgtCls = extractClassFqn(tgt);
                    if (!tgtMod.equalsIgnoreCase(filter) && !tgtPkg.equalsIgnoreCase(filter) && !tgtCls.equalsIgnoreCase(filter) && !tgt.contains(filter)) {
                        continue;
                    }
                }
                allMethods.add(tgt);
                methodCalls.computeIfAbsent(v, k -> new LinkedHashMap<>())
                           .merge(tgt, 1, Integer::sum);
            }
        }

        List<String> methodList;
        if ((filter == null || filter.isEmpty()) && allMethods.size() > 200) {
            Map<String, Integer> degrees = new HashMap<>();
            for (String m : allMethods) {
                int deg = g.inDegreeOf(m) + g.outDegreeOf(m);
                degrees.put(m, deg);
            }
            methodList = allMethods.stream()
                .sorted(Comparator.comparingInt((String m) -> degrees.getOrDefault(m, 0)).reversed())
                .limit(200)
                .sorted()
                .collect(Collectors.toList());
        } else {
            methodList = new ArrayList<>(allMethods);
        }

        Map<String, Integer> indexMap = new HashMap<>();
        for (int i = 0; i < methodList.size(); i++) indexMap.put(methodList.get(i), i);

        int n = methodList.size();
        int[][] matrix = new int[n][n];
        for (Map.Entry<String, Map<String, Integer>> srcEntry : methodCalls.entrySet()) {
            Integer si = indexMap.get(srcEntry.getKey());
            if (si == null) continue;
            for (Map.Entry<String, Integer> tgtEntry : srcEntry.getValue().entrySet()) {
                Integer ti = indexMap.get(tgtEntry.getKey());
                if (ti == null) continue;
                matrix[si][ti] = tgtEntry.getValue();
            }
        }

        Map<String, String> methodContainers = new LinkedHashMap<>();
        for (String m : methodList) {
            methodContainers.put(m, extractClassFqn(m));
        }

        return new DSMPayload(methodList, matrix, methodContainers, "methods");
    }

    public static class DSMSparseCell {
        public final int r;
        public final int c;
        public final int v;
        public final boolean isCycle;

        public DSMSparseCell(int r, int c, int v, boolean isCycle) {
            this.r = r; this.c = c; this.v = v; this.isCycle = isCycle;
        }

        public DSMSparseCell(int r, int c, int v) {
            this(r, c, v, false);
        }
    }

    /** DSM response payload. */
    public static class DSMPayload {
        public final List<String> classes;
        public final int[][]      matrix;
        public final Map<String, String> packages;
        public final String scope;
        public final List<DSMSparseCell> cells;
        public final int totalDependencies;
        public final int cycleCount;
        public final double acyclicityRating;
        public final List<String> cyclesList;

        public DSMPayload(List<String> classes, int[][] matrix, Map<String, String> packages, String scope) {
            this.classes = classes != null ? classes : Collections.emptyList();
            this.matrix  = matrix;
            this.packages = packages != null ? packages : Collections.emptyMap();
            this.scope = scope;
            this.cells = new ArrayList<>();
            List<String> cycles = new ArrayList<>();
            int totalDeps = 0;
            int cycleCells = 0;

            if (matrix != null) {
                int n = matrix.length;
                for (int r = 0; r < n; r++) {
                    for (int c = 0; c < matrix[r].length; c++) {
                        int val = matrix[r][c];
                        if (val > 0) {
                            totalDeps++;
                            boolean cycle = (r != c && c < n && r < matrix[c].length && matrix[c][r] > 0);
                            if (cycle) {
                                cycleCells++;
                                if (r < c && r < this.classes.size() && c < this.classes.size()) {
                                    cycles.add(this.classes.get(r) + " <-> " + this.classes.get(c));
                                }
                            }
                            this.cells.add(new DSMSparseCell(r, c, val, cycle));
                        }
                    }
                }
            }
            this.totalDependencies = totalDeps;
            this.cycleCount = cycles.size();
            this.cyclesList = cycles;
            this.acyclicityRating = totalDeps > 0
                ? Math.round((1.0 - ((double) cycleCells / totalDeps)) * 1000.0) / 10.0
                : 100.0;
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

