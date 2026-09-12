package com.codelens.analysis;

import com.codelens.core.model.CodeMethod;
import com.codelens.core.model.CodeType;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * CriticalPathAnalyzer - Identifies and extracts critical transaction paths for persistent classes.
 *
 * Provides:
 * 1. Persistent class detection (Get, Create, Modify contract, annotations, naming).
 * 2. Multi-hop call chain tracing from entry points (BT, ET, PS, Controllers) to persistent entities.
 * 3. Downstream side-effect tracing (AuditTrailService, Event sinks, Persistence sinks).
 * 4. Multi-dimensional path categorization: Primary, Mutation, Read, Longest, Max Complexity.
 * 5. Precomputed deterministic visual layout coordinates for instant canvas rendering.
 */
public class CriticalPathAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(CriticalPathAnalyzer.class);

    private final CallGraphAnalyzer callGraphAnalyzer;

    public CriticalPathAnalyzer(CallGraphAnalyzer callGraphAnalyzer) {
        this.callGraphAnalyzer = callGraphAnalyzer;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DTO Models
    // ─────────────────────────────────────────────────────────────────────────

    public static class CriticalPathNode {
        public String id;
        public String label;
        public String classFqn;
        public String simpleName;
        public String packageFqn;
        public String kind;               // METHOD | CLASS
        public String role;               // ENTRY_POINT | INTERMEDIARY | PERSISTENT_TARGET | DOWNSTREAM_SINK
        public int complexity;
        public int step;                  // 1-based order in path
        public String archetypeBadge;     // MUTATE | FETCH | BATCH | PERSISTENT | AUDIT | SERVICE | DATA-GRABBER
        public String archetypeColor;
        public String archetypeIcon;
        public Double x;
        public Double y;

        public CriticalPathNode() {}

        public CriticalPathNode(String id, String label, String classFqn, String simpleName,
                                String packageFqn, String kind, String role, int complexity,
                                int step, String archetypeBadge, String archetypeColor,
                                String archetypeIcon, Double x, Double y) {
            this.id = id;
            this.label = label;
            this.classFqn = classFqn;
            this.simpleName = simpleName;
            this.packageFqn = packageFqn;
            this.kind = kind;
            this.role = role;
            this.complexity = complexity;
            this.step = step;
            this.archetypeBadge = archetypeBadge;
            this.archetypeColor = archetypeColor;
            this.archetypeIcon = archetypeIcon;
            this.x = x;
            this.y = y;
        }
    }

    public static class CriticalPathEdge {
        public String source;
        public String target;
        public String kind;               // CALLS
        public int step;                  // Step index along the path

        public CriticalPathEdge() {}

        public CriticalPathEdge(String source, String target, String kind, int step) {
            this.source = source;
            this.target = target;
            this.kind = kind;
            this.step = step;
        }
    }

    public static class CriticalPathMetrics {
        public int length;
        public int cumulativeComplexity;
        public String entryPoint;
        public String targetMethod;
        public int upstreamHops;
        public int downstreamHops;
        public double riskScore;
        public List<String> bottlenecks = new ArrayList<>();
    }

    public static class CriticalPath {
        public String pathId;             // primary | mutation | read | longest | max_complexity
        public String category;           // PRIMARY | MUTATION | READ | LONGEST | MAX_COMPLEXITY
        public String title;
        public String description;
        public CriticalPathMetrics metrics;
        public List<CriticalPathNode> nodes = new ArrayList<>();
        public List<CriticalPathEdge> edges = new ArrayList<>();
    }

    public static class CriticalPathReport {
        public String targetClassFqn;
        public String targetClassSimpleName;
        public String packageFqn;
        public boolean isPersistent;
        public List<String> persistentMethods = new ArrayList<>();
        public CriticalPath primaryPath;
        public List<CriticalPath> candidatePaths = new ArrayList<>();
        public List<CallGraphAnalyzer.GraphNode> graphNodes = new ArrayList<>();
        public List<CallGraphAnalyzer.GraphEdge> graphEdges = new ArrayList<>();
    }

    public static class PersistentClassSummary {
        public String fqn;
        public String simpleName;
        public String packageFqn;
        public int lineCount;
        public int methodCount;
        public int fieldCount;
        public List<String> persistentMethods = new ArrayList<>();
        public String primaryEntryPoint;
        public int criticalPathLength;
        public int maxComplexity;
        public double riskScore;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistent Class Identification
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Identifies all persistent classes across the codebase based on:
     * 1. BaNCS Persistent Entity contract: possesses Get, Create, Modify methods.
     * 2. Naming convention (e.g. starts with PC_ or ends with Entity).
     * 3. Annotations (@Entity, @Table).
     */
    public List<PersistentClassSummary> findPersistentClasses(List<CodeType> allTypes,
                                                             List<CodeMethod> allMethods) {
        Map<String, List<CodeMethod>> methodsByType = new HashMap<>();
        Map<String, CodeMethod> methodByFqn = new HashMap<>();
        for (CodeMethod m : allMethods) {
            methodsByType.computeIfAbsent(m.getDeclaringTypeFqn(), k -> new ArrayList<>()).add(m);
            methodByFqn.put(m.getFqn(), m);
        }

        Map<String, CodeType> typeByFqn = new HashMap<>();
        for (CodeType t : allTypes) {
            typeByFqn.put(t.getFqn(), t);
        }

        Graph<String, DefaultEdge> graph = callGraphAnalyzer.getCallGraph();
        List<PersistentClassSummary> result = new ArrayList<>();

        for (CodeType type : allTypes) {
            if (type.getKind() != null && !type.getKind().equalsIgnoreCase("CLASS")) {
                continue;
            }

            List<CodeMethod> methods = methodsByType.getOrDefault(type.getFqn(), Collections.emptyList());
            Set<String> methodNames = new HashSet<>();
            for (CodeMethod m : methods) {
                if (m.getSimpleName() != null) {
                    String clean = m.getSimpleName().replaceAll("\\(.*\\)", "").trim().toLowerCase();
                    methodNames.add(clean);
                }
            }

            boolean hasGet = methodNames.contains("get");
            boolean hasCreate = methodNames.contains("create");
            boolean hasModify = methodNames.contains("modify");
            boolean hasContract = hasGet && hasCreate && hasModify;

            String simple = type.getSimpleName() != null ? type.getSimpleName() : "";
            // Message Objects (MO_INP_*, MO_OUT_*, MO_*) and DTOs/VOs are data transfer objects, not persistent entities
            if (simple.startsWith("MO_") || (simple.startsWith("MO") && simple.length() > 2 && Character.isUpperCase(simple.charAt(2)))
                    || simple.endsWith("DTO") || simple.endsWith("VO")) {
                continue;
            }

            boolean hasNaming = simple.startsWith("PC_") || simple.endsWith("Entity") || simple.endsWith("Record");

            if (!hasContract && !hasNaming) {
                continue;
            }

            PersistentClassSummary summary = new PersistentClassSummary();
            summary.fqn = type.getFqn();
            summary.simpleName = type.getSimpleName();
            summary.packageFqn = type.getPackageFqn();
            summary.lineCount = type.getLineCount();
            summary.methodCount = type.getMethodCount();
            summary.fieldCount = type.getFieldCount();

            if (hasGet) summary.persistentMethods.add("Get");
            if (hasCreate) summary.persistentMethods.add("Create");
            if (hasModify) summary.persistentMethods.add("Modify");

            // Quick trace of primary critical path metrics
            CriticalPathReport report = analyzeCriticalPaths(type.getFqn(), graph, methodByFqn, typeByFqn, false);
            if (report.primaryPath != null && report.primaryPath.metrics != null) {
                summary.primaryEntryPoint = report.primaryPath.metrics.entryPoint;
                summary.criticalPathLength = report.primaryPath.metrics.length;
                summary.maxComplexity = report.primaryPath.metrics.cumulativeComplexity;
                summary.riskScore = report.primaryPath.metrics.riskScore;
            } else {
                summary.primaryEntryPoint = "None";
                summary.criticalPathLength = 0;
                summary.maxComplexity = 0;
                summary.riskScore = 0.0;
            }

            result.add(summary);
        }

        // Sort by risk score descending, then by simple name
        result.sort((a, b) -> {
            int cmp = Double.compare(b.riskScore, a.riskScore);
            if (cmp != 0) return cmp;
            return a.simpleName.compareToIgnoreCase(b.simpleName);
        });

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Critical Path Analysis Engine
    // ─────────────────────────────────────────────────────────────────────────

    public CriticalPathReport analyzeCriticalPaths(String classFqn,
                                                  Graph<String, DefaultEdge> graph,
                                                  Map<String, CodeMethod> methodMap,
                                                  Map<String, CodeType> typeMap,
                                                  boolean includeAllGraphNodes) {
        CriticalPathReport report = new CriticalPathReport();
        report.targetClassFqn = classFqn;

        CodeType targetType = typeMap != null ? typeMap.get(classFqn) : null;
        report.targetClassSimpleName = targetType != null && targetType.getSimpleName() != null
            ? targetType.getSimpleName()
            : (classFqn.contains(".") ? classFqn.substring(classFqn.lastIndexOf('.') + 1) : classFqn);
        report.packageFqn = targetType != null && targetType.getPackageFqn() != null
            ? targetType.getPackageFqn()
            : (classFqn.contains(".") ? classFqn.substring(0, classFqn.lastIndexOf('.')) : "");

        if (graph == null || graph.vertexSet().isEmpty()) {
            return report;
        }

        // 1. Collect target class methods present in the graph
        List<String> targetMethods = new ArrayList<>();
        for (String v : graph.vertexSet()) {
            if (v.startsWith(classFqn + ".") || v.startsWith(classFqn + "(")) {
                targetMethods.add(v);
                String simpleM = extractSimpleMethodName(v);
                if (isPersistentMethod(simpleM)) {
                    if (!report.persistentMethods.contains(simpleM)) {
                        report.persistentMethods.add(simpleM);
                    }
                }
            }
        }
        report.isPersistent = report.persistentMethods.size() >= 2 || targetMethods.stream().anyMatch(m -> isPersistentMethod(extractSimpleMethodName(m)));

        if (targetMethods.isEmpty()) {
            return report;
        }

        // 2. Discover all upstream paths from entry points down to each target method
        List<List<String>> candidateUpstreamPaths = new ArrayList<>();
        for (String tm : targetMethods) {
            findUpstreamPaths(tm, graph, candidateUpstreamPaths, 6);
        }

        // If no multi-hop paths found, add trivial direct references
        if (candidateUpstreamPaths.isEmpty()) {
            for (String tm : targetMethods) {
                candidateUpstreamPaths.add(Collections.singletonList(tm));
            }
        }

        // 3. For each target method, find downstream side-effects (audit, sinks)
        Map<String, List<String>> downstreamMap = new HashMap<>();
        for (String tm : targetMethods) {
            List<String> down = findDownstreamPath(tm, graph, 3);
            if (!down.isEmpty()) {
                downstreamMap.put(tm, down);
            }
        }

        // 4. Assemble full End-to-End paths and score them
        List<ScoredPath> scoredPaths = new ArrayList<>();
        for (List<String> upPath : candidateUpstreamPaths) {
            String targetMethod = upPath.get(upPath.size() - 1);
            List<String> downPath = downstreamMap.getOrDefault(targetMethod, Collections.emptyList());

            List<String> fullPath = new ArrayList<>(upPath);
            fullPath.addAll(downPath);

            ScoredPath sp = scorePath(fullPath, upPath.size() - 1, methodMap);
            scoredPaths.add(sp);
        }

        if (scoredPaths.isEmpty()) {
            return report;
        }

        // 5. Select distinct candidate paths
        // A. Primary: Highest overall score
        scoredPaths.sort((a, b) -> Double.compare(b.score, a.score));
        ScoredPath primaryScored = scoredPaths.get(0);
        report.primaryPath = buildCriticalPathDto("primary", "PRIMARY",
            "Primary Critical Path (Highest Impact)",
            "Deepest and highest-complexity transactional path driving this persistent entity.",
            primaryScored, methodMap, typeMap);

        // B. Mutation Path: Top path hitting Create or Modify or BT
        ScoredPath mutationScored = scoredPaths.stream()
            .filter(p -> p.isMutation)
            .findFirst()
            .orElse(null);
        if (mutationScored != null && mutationScored != primaryScored) {
            report.candidatePaths.add(buildCriticalPathDto("mutation", "MUTATION",
                "Mutation Write Path (State Change)",
                "Transaction path executing state modification (Create/Modify) on this persistent entity.",
                mutationScored, methodMap, typeMap));
        }

        // C. Read / Query Path: Top path hitting Get or ET or Data Grabbers
        ScoredPath readScored = scoredPaths.stream()
            .filter(p -> p.isRead)
            .findFirst()
            .orElse(null);
        if (readScored != null && readScored != primaryScored && readScored != mutationScored) {
            report.candidatePaths.add(buildCriticalPathDto("read", "READ",
                "Read / Hydration Path (Query)",
                "Query path retrieving and hydrating persistent entity state.",
                readScored, methodMap, typeMap));
        }

        // D. Longest Execution Chain
        ScoredPath longestScored = scoredPaths.stream()
            .max(Comparator.comparingInt(p -> p.path.size()))
            .orElse(null);
        if (longestScored != null && longestScored != primaryScored && longestScored != mutationScored && longestScored != readScored) {
            report.candidatePaths.add(buildCriticalPathDto("longest", "LONGEST",
                "Longest Propagation Chain",
                "Maximum call sequence depth from an external entry point down to persistent persistence.",
                longestScored, methodMap, typeMap));
        }

        // E. Maximum Complexity Path
        ScoredPath maxComplexityScored = scoredPaths.stream()
            .max(Comparator.comparingInt(p -> p.cumulativeComplexity))
            .orElse(null);
        if (maxComplexityScored != null && maxComplexityScored != primaryScored &&
            maxComplexityScored != mutationScored && maxComplexityScored != readScored && maxComplexityScored != longestScored) {
            report.candidatePaths.add(buildCriticalPathDto("max_complexity", "MAX_COMPLEXITY",
                "Maximum Complexity Path",
                "Path accumulating the highest cyclomatic business logic complexity.",
                maxComplexityScored, methodMap, typeMap));
        }

        // Always ensure primary is in candidatePaths list too for switcher UI
        report.candidatePaths.add(0, report.primaryPath);

        // 6. Build combined graph nodes and edges for rendering
        Map<String, CallGraphAnalyzer.GraphNode> combinedNodes = new LinkedHashMap<>();
        Map<String, CallGraphAnalyzer.GraphEdge> combinedEdges = new LinkedHashMap<>();

        for (CriticalPath cp : report.candidatePaths) {
            for (CriticalPathNode n : cp.nodes) {
                if (!combinedNodes.containsKey(n.id)) {
                    CallGraphAnalyzer.GraphNode gn = new CallGraphAnalyzer.GraphNode(
                        n.id, n.label, n.role, n.kind, n.x, n.y
                    );
                    gn.packageFqn = n.packageFqn;
                    combinedNodes.put(n.id, gn);
                }
            }
            for (CriticalPathEdge e : cp.edges) {
                String edgeKey = e.source + "->" + e.target;
                if (!combinedEdges.containsKey(edgeKey)) {
                    combinedEdges.put(edgeKey, new CallGraphAnalyzer.GraphEdge(e.source, e.target, e.kind));
                }
            }
        }

        report.graphNodes = new ArrayList<>(combinedNodes.values());
        report.graphEdges = new ArrayList<>(combinedEdges.values());

        return report;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Path Traversal Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void findUpstreamPaths(String targetNode,
                                   Graph<String, DefaultEdge> graph,
                                   List<List<String>> results,
                                   int maxDepth) {
        Queue<List<String>> queue = new LinkedList<>();
        queue.add(Collections.singletonList(targetNode));

        int pathLimit = 60;

        while (!queue.isEmpty() && results.size() < pathLimit) {
            List<String> current = queue.poll();
            String head = current.get(0);

            Set<DefaultEdge> incoming = graph.incomingEdgesOf(head);
            boolean isEntry = isEntryPoint(head, incoming.isEmpty());

            if (isEntry || current.size() >= maxDepth || incoming.isEmpty()) {
                if (current.size() > 1 || isEntry) {
                    results.add(current);
                }
                continue;
            }

            for (DefaultEdge edge : incoming) {
                String source = graph.getEdgeSource(edge);
                if (!current.contains(source)) { // Cycle prevention
                    List<String> next = new ArrayList<>(current.size() + 1);
                    next.add(source);
                    next.addAll(current);
                    queue.add(next);
                }
            }
        }
    }

    private List<String> findDownstreamPath(String startNode,
                                            Graph<String, DefaultEdge> graph,
                                            int maxDepth) {
        List<String> path = new ArrayList<>();
        String curr = startNode;
        Set<String> visited = new HashSet<>();
        visited.add(curr);

        while (path.size() < maxDepth) {
            Set<DefaultEdge> outgoing = graph.outgoingEdgesOf(curr);
            if (outgoing.isEmpty()) break;

            String bestNext = null;
            for (DefaultEdge edge : outgoing) {
                String target = graph.getEdgeTarget(edge);
                if (visited.contains(target)) continue;

                // Prefer audit or persistence sinks
                if (target.contains("Audit") || target.contains("Log") || target.contains("Event") || target.contains("Store")) {
                    bestNext = target;
                    break;
                }
                if (bestNext == null) {
                    bestNext = target;
                }
            }

            if (bestNext == null) break;
            path.add(bestNext);
            visited.add(bestNext);
            curr = bestNext;

            // Stop if reached audit or persistence sink
            if (curr.contains("Audit") || curr.contains("Event") || curr.contains("Database")) {
                break;
            }
        }
        return path;
    }

    private boolean isEntryPoint(String fqn, boolean zeroInDegree) {
        if (zeroInDegree) return true;
        String simple = extractSimpleMethodName(fqn);
        String upper = fqn.toUpperCase();
        return upper.contains("CONTROLLER") || upper.contains("RESOURCE")
            || upper.contains("ENDPOINT") || simple.equals("main");
    }

    private static boolean isPersistentMethod(String name) {
        if (name == null) return false;
        String s = name.trim().toLowerCase();
        return s.equals("get") || s.equals("create") || s.equals("modify") || s.equals("save")
            || s.equals("delete") || s.equals("update") || s.equals("findbyid") || s.equals("persist");
    }

    private static String extractSimpleMethodName(String fqn) {
        if (fqn == null) return "";
        int paren = fqn.indexOf('(');
        String clean = paren > 0 ? fqn.substring(0, paren) : fqn;
        int dot = clean.lastIndexOf('.');
        return dot >= 0 ? clean.substring(dot + 1) : clean;
    }

    private static String extractClassFqn(String methodFqn) {
        if (methodFqn == null) return "";
        int paren = methodFqn.indexOf('(');
        String clean = paren > 0 ? methodFqn.substring(0, paren) : methodFqn;
        int dot = clean.lastIndexOf('.');
        return dot >= 0 ? clean.substring(0, dot) : clean;
    }

    private static String extractPackageFqn(String classFqn) {
        if (classFqn == null) return "";
        int dot = classFqn.lastIndexOf('.');
        return dot >= 0 ? classFqn.substring(0, dot) : "";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Path Scoring & DTO Assembly
    // ─────────────────────────────────────────────────────────────────────────

    private static class ScoredPath {
        List<String> path;
        int targetIndex;
        int cumulativeComplexity;
        boolean isMutation;
        boolean isRead;
        double score;
    }

    private ScoredPath scorePath(List<String> path, int targetIndex, Map<String, CodeMethod> methodMap) {
        ScoredPath sp = new ScoredPath();
        sp.path = path;
        sp.targetIndex = targetIndex;

        int cc = 0;
        double score = 0.0;

        for (String node : path) {
            CodeMethod m = methodMap != null ? methodMap.get(node) : null;
            int c = (m != null && m.getCyclomaticComplexity() > 0) ? m.getCyclomaticComplexity() : 1;
            cc += c;
        }
        sp.cumulativeComplexity = cc;

        String targetMethod = path.get(targetIndex);
        String targetName = extractSimpleMethodName(targetMethod).toLowerCase();
        String entryMethod = path.get(0);
        String entryName = extractSimpleMethodName(entryMethod).toUpperCase();

        if (targetName.equals("modify") || targetName.equals("create") || targetName.contains("update") || entryName.contains("BT")) {
            sp.isMutation = true;
            score += 50.0;
        }

        if (targetName.equals("get") || targetName.contains("find") || targetName.contains("fetch") || entryName.contains("ET") || entryName.contains("DG")) {
            sp.isRead = true;
            score += 20.0;
        }

        if (entryName.contains("PS") || entryName.contains("BATCH")) {
            score += 35.0;
        }

        // Check for downstream audit
        boolean hasAudit = path.stream().anyMatch(n -> n.contains("Audit") || n.contains("Log") || n.contains("Event"));
        if (hasAudit) {
            score += 25.0;
        }

        // Hops and complexity contribution
        score += cc * 1.5;
        score += path.size() * 5.0;

        sp.score = score;
        return sp;
    }

    private CriticalPath buildCriticalPathDto(String pathId,
                                              String category,
                                              String title,
                                              String description,
                                              ScoredPath scored,
                                              Map<String, CodeMethod> methodMap,
                                              Map<String, CodeType> typeMap) {
        CriticalPath cp = new CriticalPath();
        cp.pathId = pathId;
        cp.category = category;
        cp.title = title;
        cp.description = description;

        cp.metrics = new CriticalPathMetrics();
        cp.metrics.length = Math.max(0, scored.path.size() - 1);
        cp.metrics.cumulativeComplexity = scored.cumulativeComplexity;
        cp.metrics.entryPoint = scored.path.get(0);
        cp.metrics.targetMethod = scored.path.get(scored.targetIndex);
        cp.metrics.upstreamHops = scored.targetIndex;
        cp.metrics.downstreamHops = Math.max(0, scored.path.size() - 1 - scored.targetIndex);
        cp.metrics.riskScore = scored.score;

        int totalHops = scored.path.size();
        double startX = 100.0;
        double stepX = 260.0;
        double centerY = 280.0;

        for (int i = 0; i < totalHops; i++) {
            String fqn = scored.path.get(i);
            String simpleName = extractSimpleMethodName(fqn);
            String classFqn = extractClassFqn(fqn);
            String packageFqn = extractPackageFqn(classFqn);

            CodeMethod m = methodMap != null ? methodMap.get(fqn) : null;
            int complexity = (m != null && m.getCyclomaticComplexity() > 0) ? m.getCyclomaticComplexity() : 1;

            if (complexity > 8) {
                cp.metrics.bottlenecks.add(simpleName + " (CC: " + complexity + ")");
            }

            String role;
            if (i == 0) {
                role = "ENTRY_POINT";
            } else if (i == scored.targetIndex) {
                role = "PERSISTENT_TARGET";
            } else if (i > scored.targetIndex) {
                role = "DOWNSTREAM_SINK";
            } else {
                role = "INTERMEDIARY";
            }

            ArchetypeStyle arch = classifyArchetype(fqn, simpleName, role);

            // Precomputed layout coordinate along an aesthetic wave curve
            double x = startX + i * stepX;
            double wave = Math.sin((i / (double) Math.max(1, totalHops)) * Math.PI) * 45.0;
            double y = centerY + (i % 2 == 1 ? wave : -wave);

            CriticalPathNode node = new CriticalPathNode(
                fqn,
                simpleName,
                classFqn,
                simpleName,
                packageFqn,
                "METHOD",
                role,
                complexity,
                i + 1,
                arch.badge,
                arch.color,
                arch.icon,
                Math.round(x * 10.0) / 10.0,
                Math.round(y * 10.0) / 10.0
            );
            cp.nodes.add(node);

            if (i > 0) {
                String prevFqn = scored.path.get(i - 1);
                cp.edges.add(new CriticalPathEdge(prevFqn, fqn, "CALLS", i));
            }
        }

        return cp;
    }

    private static class ArchetypeStyle {
        String badge;
        String color;
        String icon;

        ArchetypeStyle(String badge, String color, String icon) {
            this.badge = badge;
            this.color = color;
            this.icon = icon;
        }
    }

    private ArchetypeStyle classifyArchetype(String fqn, String name, String role) {
        String nUpper = (name != null ? name : "").toUpperCase();
        String fUpper = (fqn != null ? fqn : "").toUpperCase();
        if ("PERSISTENT_TARGET".equals(role)) {
            return new ArchetypeStyle("PERSISTENT", "#6366f1", "database");
        }
        if ("DOWNSTREAM_SINK".equals(role) || fUpper.contains("AUDIT") || fUpper.contains("LOG") || fUpper.contains("EVENT")) {
            return new ArchetypeStyle("AUDIT", "#ef4444", "shield");
        }
        if (matchesBaNCSArchetype(fqn, name, "BT") || nUpper.startsWith("MODIFY") || nUpper.startsWith("CREATE")) {
            return new ArchetypeStyle("MUTATE", "#f59e0b", "zap");
        }
        if (matchesBaNCSArchetype(fqn, name, "ET") || nUpper.startsWith("GET") || nUpper.startsWith("FETCH")) {
            return new ArchetypeStyle("FETCH", "#10b981", "download");
        }
        if (matchesBaNCSArchetype(fqn, name, "TO") || nUpper.endsWith("TO") || nUpper.contains("OWNTASK") || fUpper.contains("TASKOWN")) {
            return new ArchetypeStyle("TASK-OWN", "#0ea5e9", "checkSquare");
        }
        if (matchesBaNCSArchetype(fqn, name, "TC") || nUpper.endsWith("TC") || nUpper.contains("COMMONTASK") || fUpper.contains("TASKCOMMON")) {
            return new ArchetypeStyle("TASK-COMMON", "#a855f7", "share2");
        }
        if (matchesBaNCSArchetype(fqn, name, "PS") || fUpper.contains("BATCH")) {
            return new ArchetypeStyle("BATCH", "#8b5cf6", "settings");
        }
        if (matchesBaNCSArchetype(fqn, name, "PB")) {
            return new ArchetypeStyle("PRE-BATCH", "#3b82f6", "skipBack");
        }
        if (matchesBaNCSArchetype(fqn, name, "PA")) {
            return new ArchetypeStyle("POST-BATCH", "#ec4899", "skipForward");
        }
        if (matchesBaNCSArchetype(fqn, name, "DG") || fUpper.contains("GRABBER")) {
            return new ArchetypeStyle("DATA-GRABBER", "#06b6d4", "box");
        }
        if (fUpper.contains("CONTROLLER") || "ENTRY_POINT".equals(role)) {
            return new ArchetypeStyle("ENTRY", "#38bdf8", "globe");
        }
        return new ArchetypeStyle("SERVICE", "#64748b", "layers");
    }

    private static boolean matchesBaNCSArchetype(String fqn, String name, String suffix) {
        if (fqn == null && name == null) return false;
        String n = name != null ? name : "";
        String f = fqn != null ? fqn : "";
        // Match method simple name starting with module + suffix: e.g. AMTO..., CMTC..., AMBT..., SCPS...
        if (n.matches("^[A-Z]{2,4}" + suffix + "[A-Z0-9_].*") || n.matches("^[A-Z]{2,4}" + suffix + "$")) {
            return true;
        }
        // Match class segment in FQN: e.g. .AMTO..., .CMTC..., .AMBT...
        if (f.matches(".*\\.[A-Z]{2,4}" + suffix + "[A-Z0-9_].*")) {
            return true;
        }
        return false;
    }
}
