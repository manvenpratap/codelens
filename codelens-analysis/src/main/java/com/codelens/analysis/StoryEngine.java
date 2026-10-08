package com.codelens.analysis;

import com.codelens.core.model.CodeMethod;
import com.codelens.core.model.CodeType;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * StoryEngine - Converts graph execution paths into structured, evidence-backed narrative storylines.
 *
 * Implements:
 * - CodeStory Master Specification Section 8: The Signature UI Concept (The Storyline)
 * - CodeStory Master Specification Section 12: The Story Engine (Transforming graph paths into human-readable narratives)
 * - CodeStory Master Specification Section 20 & 36: Workflows to Storylines with line-level evidence grounding.
 */
public class StoryEngine {

    private static final Logger log = LoggerFactory.getLogger(StoryEngine.class);

    public enum StepRole {
        ENTRY("ENTRY", "Entrypoint", "#388bfd", "⚡"),
        VALIDATION("VALIDATION", "Validation & Guard", "#c084fc", "🛡️"),
        BUSINESS_LOGIC("BUSINESS_LOGIC", "Domain Service", "#fbbf24", "⚙️"),
        PERSISTENCE("PERSISTENCE", "Database / Storage", "#10b981", "💾"),
        AUDIT_EVENT("AUDIT_EVENT", "Audit & Event Sink", "#f43f5e", "📢"),
        PROCESSING("PROCESSING", "Internal Processing", "#64748b", "🔄");

        public final String key;
        public final String label;
        public final String color;
        public final String icon;

        StepRole(String key, String label, String color, String icon) {
            this.key = key;
            this.label = label;
            this.color = color;
            this.icon = icon;
        }
    }

    public static class StorylineStep {
        public int stepIndex;
        public String entityFqn;
        public String methodFqn;
        public String simpleName;
        public String classFqn;
        public String classSimpleName;
        public String sourceFile;
        public int startLine;
        public String role;
        public String roleLabel;
        public String roleColor;
        public String roleIcon;
        public String narrativeAction;
        public int callersCount;
        public int calleesCount;
        public int complexity;
    }

    public static class StorylineEvidence {
        public String sourceFile;
        public int line;
        public String symbol;
        public String reason;

        public StorylineEvidence() {}

        public StorylineEvidence(String sourceFile, int line, String symbol, String reason) {
            this.sourceFile = sourceFile;
            this.line = line;
            this.symbol = symbol;
            this.reason = reason;
        }
    }

    public static class Storyline {
        public String id;
        public String title;
        public String category; // TRANSACTION, BATCH_PIPELINE, PERSISTENCE_FLOW, AUDIT_FLOW, SERVICE_WORKFLOW
        public String executiveSummary;
        public String entryPointFqn;
        public String terminalSinkFqn;
        public int stepCount;
        public int totalComplexity;
        public List<StorylineStep> steps = new ArrayList<>();
        public List<StorylineEvidence> evidence = new ArrayList<>();
        public List<String> roleSequence = new ArrayList<>();
    }

    public static class StorylineSummary {
        public String id;
        public String title;
        public String category;
        public String executiveSummary;
        public String entryPoint;
        public String entryClass;
        public String terminalSink;
        public int stepCount;
        public int totalComplexity;
        public List<String> roleSequence = new ArrayList<>();
    }

    public static class ChangeImpactStory {
        public String targetMethod;
        public String targetClass;
        public String impactNarrative;
        public List<String> affectedStorylines = new ArrayList<>();
        public List<String> upstreamCallers = new ArrayList<>();
        public List<String> downstreamCallees = new ArrayList<>();
        public List<String> coveringTests = new ArrayList<>();
        public int totalAffectedWorkflows;
        public int blastRadiusCount;
    }

    public static class TeachMeGuide {
        public String storylineTitle;
        public String category;
        public String level1ExecutiveOverview;
        public List<String> level2ArchitectureComponents = new ArrayList<>();
        public List<String> level3ExecutionFlow = new ArrayList<>();
        public List<StorylineStep> level4CodeDetails = new ArrayList<>();
        public List<StorylineEvidence> level5Evidence = new ArrayList<>();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Storyline Discovery & Synthesis
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Discovers top end-to-end storylines across the scanned codebase.
     */
    public List<StorylineSummary> discoverStorylines(List<CodeType> types,
                                                     List<CodeMethod> methods,
                                                     Graph<String, DefaultEdge> graph) {
        if (graph == null || graph.vertexSet().isEmpty() || methods == null || methods.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, CodeMethod> methodMap = new HashMap<>(methods.size());
        for (CodeMethod m : methods) {
            methodMap.put(m.getFqn(), m);
        }

        Map<String, CodeType> typeMap = new HashMap<>(types != null ? types.size() : 16);
        if (types != null) {
            for (CodeType t : types) {
                typeMap.put(t.getFqn(), t);
            }
        }

        // 1. Identify candidate entry points
        List<String> entryCandidates = findEntryPoints(graph, methodMap, typeMap);

        // 2. Extract best representative paths for each entry point
        List<Storyline> storylines = new ArrayList<>();
        Set<String> seenEntryClasses = new HashSet<>();

        for (String entryFqn : entryCandidates) {
            CodeMethod entryM = methodMap.get(entryFqn);
            String declType = entryM != null ? entryM.getDeclaringTypeFqn() : "";
            if (seenEntryClasses.contains(declType) && storylines.size() >= 30) {
                continue;
            }

            List<String> path = traceSignificantPath(entryFqn, graph, methodMap);
            if (path != null && path.size() >= 2) {
                Storyline story = buildStorylineFromPath("story-" + (storylines.size() + 1), path, methodMap, typeMap);
                if (story != null && story.steps.size() >= 2) {
                    storylines.add(story);
                    seenEntryClasses.add(declType);
                }
            }

            if (storylines.size() >= 40) {
                break;
            }
        }

        // Sort storylines by step count descending, then total complexity
        storylines.sort((a, b) -> {
            int cmp = Integer.compare(b.stepCount, a.stepCount);
            if (cmp != 0) return cmp;
            return Integer.compare(b.totalComplexity, a.totalComplexity);
        });

        // Convert to summaries
        List<StorylineSummary> summaries = new ArrayList<>(storylines.size());
        for (Storyline s : storylines) {
            StorylineSummary sum = new StorylineSummary();
            sum.id = s.id;
            sum.title = s.title;
            sum.category = s.category;
            sum.executiveSummary = s.executiveSummary;
            sum.entryPoint = s.entryPointFqn;
            sum.entryClass = s.steps.isEmpty() ? "" : s.steps.get(0).classSimpleName;
            sum.terminalSink = s.terminalSinkFqn;
            sum.stepCount = s.stepCount;
            sum.totalComplexity = s.totalComplexity;
            sum.roleSequence = s.roleSequence;
            summaries.add(sum);
        }

        return summaries;
    }

    /**
     * Builds a detailed interactive Storyline given an entry method or target entity FQN.
     */
    public Storyline getStorylineByFqn(String fqn,
                                       List<CodeType> types,
                                       List<CodeMethod> methods,
                                       Graph<String, DefaultEdge> graph) {
        if (graph == null || fqn == null || fqn.isBlank()) {
            return null;
        }

        Map<String, CodeMethod> methodMap = new HashMap<>(methods.size());
        for (CodeMethod m : methods) {
            methodMap.put(m.getFqn(), m);
        }

        Map<String, CodeType> typeMap = new HashMap<>(types != null ? types.size() : 16);
        if (types != null) {
            for (CodeType t : types) {
                typeMap.put(t.getFqn(), t);
            }
        }

        // Determine starting method: either fqn itself if it's a method, or first method in class
        String targetMethodFqn = fqn;
        if (!graph.containsVertex(targetMethodFqn)) {
            for (String v : graph.vertexSet()) {
                if (v.startsWith(fqn + ".") || v.startsWith(fqn + "(")) {
                    targetMethodFqn = v;
                    break;
                }
            }
        }

        if (!graph.containsVertex(targetMethodFqn)) {
            return null;
        }

        List<String> path = traceSignificantPath(targetMethodFqn, graph, methodMap);
        if (path == null || path.isEmpty()) {
            path = List.of(targetMethodFqn);
        }

        return buildStorylineFromPath("story-custom", path, methodMap, typeMap);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal Path Tracing & Role Classification
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> findEntryPoints(Graph<String, DefaultEdge> graph,
                                         Map<String, CodeMethod> methodMap,
                                         Map<String, CodeType> typeMap) {
        List<String> entries = new ArrayList<>();
        List<String> zeroInDegree = new ArrayList<>();

        for (String v : graph.vertexSet()) {
            int inDegree = graph.inDegreeOf(v);
            int outDegree = graph.outDegreeOf(v);

            // Must call at least one other method to form a story
            if (outDegree == 0) continue;

            CodeMethod m = methodMap.get(v);
            String mName = m != null && m.getSimpleName() != null ? m.getSimpleName() : extractSimpleName(v);
            String declTypeFqn = m != null ? m.getDeclaringTypeFqn() : extractClassFqn(v);
            CodeType t = typeMap.get(declTypeFqn);
            String tName = t != null && t.getSimpleName() != null ? t.getSimpleName() : extractClassSimple(declTypeFqn);

            boolean isController = tName.endsWith("Controller") || tName.endsWith("Endpoint") || tName.endsWith("Resource");
            boolean isBatch = tName.contains("Batch") || tName.contains("Scheduler") || tName.contains("Job");
            boolean isBT = tName.startsWith("BT_") || tName.endsWith("BT") || tName.startsWith("PS_");
            boolean isMain = "main".equalsIgnoreCase(mName) || mName.startsWith("run") || mName.startsWith("execute");

            if (isController || isBatch || isBT || isMain) {
                entries.add(v);
            } else if (inDegree == 0) {
                zeroInDegree.add(v);
            }
        }

        // Prioritize explicit architectural entrypoints, then 0-in-degree nodes
        List<String> combined = new ArrayList<>(entries);
        for (String z : zeroInDegree) {
            if (!combined.contains(z)) {
                combined.add(z);
            }
            if (combined.size() >= 100) break;
        }

        return combined;
    }

    private List<String> traceSignificantPath(String startVertex,
                                              Graph<String, DefaultEdge> graph,
                                              Map<String, CodeMethod> methodMap) {
        if (!graph.containsVertex(startVertex)) return Collections.emptyList();

        List<String> bestPath = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        List<String> currentPath = new ArrayList<>();

        dfsDeepestPath(startVertex, graph, methodMap, visited, currentPath, bestPath, 0, 8);

        return bestPath;
    }

    private void dfsDeepestPath(String current,
                                Graph<String, DefaultEdge> graph,
                                Map<String, CodeMethod> methodMap,
                                Set<String> visited,
                                List<String> currentPath,
                                List<String> bestPath,
                                int depth,
                                int maxDepth) {
        visited.add(current);
        currentPath.add(current);

        if (currentPath.size() > bestPath.size()) {
            bestPath.clear();
            bestPath.addAll(currentPath);
        }

        if (depth < maxDepth) {
            Set<DefaultEdge> outgoing = graph.outgoingEdgesOf(current);
            // Sort outgoing by preference for persistence, validation, or service sinks
            List<DefaultEdge> edges = new ArrayList<>(outgoing);
            edges.sort((e1, e2) -> {
                String t1 = graph.getEdgeTarget(e1);
                String t2 = graph.getEdgeTarget(e2);
                int score1 = rateSinkRelevance(t1);
                int score2 = rateSinkRelevance(t2);
                return Integer.compare(score2, score1);
            });

            for (DefaultEdge e : edges) {
                String target = graph.getEdgeTarget(e);
                if (!visited.contains(target)) {
                    dfsDeepestPath(target, graph, methodMap, visited, currentPath, bestPath, depth + 1, maxDepth);
                }
            }
        }

        currentPath.remove(currentPath.size() - 1);
        visited.remove(current);
    }

    private int rateSinkRelevance(String fqn) {
        String lower = fqn.toLowerCase(Locale.ROOT);
        if (lower.contains("dao") || lower.contains("repository") || lower.contains("save") || lower.contains("create") || lower.contains("insert")) return 5;
        if (lower.contains("audit") || lower.contains("event") || lower.contains("publish")) return 4;
        if (lower.contains("service") || lower.contains("process") || lower.contains("validate")) return 3;
        return 1;
    }

    private Storyline buildStorylineFromPath(String id,
                                             List<String> path,
                                             Map<String, CodeMethod> methodMap,
                                             Map<String, CodeType> typeMap) {
        if (path == null || path.isEmpty()) return null;

        Storyline story = new Storyline();
        story.id = id;
        story.entryPointFqn = path.get(0);
        story.terminalSinkFqn = path.get(path.size() - 1);
        story.stepCount = path.size();

        int cumulativeComplexity = 0;
        List<String> serviceNames = new ArrayList<>();
        List<String> persistNames = new ArrayList<>();
        List<String> auditNames = new ArrayList<>();

        for (int i = 0; i < path.size(); i++) {
            String v = path.get(i);
            CodeMethod m = methodMap.get(v);
            String declTypeFqn = m != null ? m.getDeclaringTypeFqn() : extractClassFqn(v);
            CodeType t = typeMap.get(declTypeFqn);

            StorylineStep step = new StorylineStep();
            step.stepIndex = i + 1;
            step.entityFqn = v;
            step.methodFqn = v;
            step.simpleName = m != null && m.getSimpleName() != null ? m.getSimpleName() : extractSimpleName(v);
            step.classFqn = declTypeFqn;
            step.classSimpleName = t != null && t.getSimpleName() != null ? t.getSimpleName() : extractClassSimple(declTypeFqn);
            step.sourceFile = t != null ? t.getSourceFile() : "";
            step.startLine = m != null ? m.getStartLine() : (t != null ? t.getStartLine() : 1);
            step.complexity = m != null ? m.getCyclomaticComplexity() : 1;
            cumulativeComplexity += step.complexity;

            // Classify step role
            step.role = classifyRole(i, path.size(), step.simpleName, step.classSimpleName).key;
            StepRole roleEnum = StepRole.valueOf(step.role);
            step.roleLabel = roleEnum.label;
            step.roleColor = roleEnum.color;
            step.roleIcon = roleEnum.icon;

            // Narrative action sentence
            step.narrativeAction = generateStepAction(roleEnum, step.simpleName, step.classSimpleName);

            story.steps.add(step);
            story.roleSequence.add(roleEnum.key);

            // Record evidence citation
            if (step.sourceFile != null && !step.sourceFile.isBlank()) {
                story.evidence.add(new StorylineEvidence(
                    step.sourceFile,
                    step.startLine,
                    step.classSimpleName + "." + step.simpleName,
                    "Step " + step.stepIndex + " (" + roleEnum.label + ") execution node"
                ));
            }

            // Categorize for executive narrative
            if (roleEnum == StepRole.BUSINESS_LOGIC) serviceNames.add(step.classSimpleName);
            else if (roleEnum == StepRole.PERSISTENCE) persistNames.add(step.classSimpleName);
            else if (roleEnum == StepRole.AUDIT_EVENT) auditNames.add(step.classSimpleName);
        }

        story.totalComplexity = cumulativeComplexity;

        // Categorize storyline
        story.category = determineStoryCategory(story.roleSequence);

        // Derive friendly title
        story.title = deriveStoryTitle(story.steps.get(0).classSimpleName, story.steps.get(0).simpleName);

        // Synthesize executive narrative paragraph
        StorylineStep first = story.steps.get(0);
        StringBuilder nar = new StringBuilder();
        nar.append("Execution begins at ").append(first.simpleName).append(" in ").append(first.classSimpleName).append(". ");
        if (!serviceNames.isEmpty()) {
            nar.append("The request delegates to ").append(String.join(", ", distinct(serviceNames)))
               .append(" for business logic orchestration. ");
        }
        if (!persistNames.isEmpty()) {
            nar.append("State transitions are persisted through ").append(String.join(", ", distinct(persistNames))).append(". ");
        }
        if (!auditNames.isEmpty()) {
            nar.append("Downstream notifications and audit entries are dispatched via ").append(String.join(", ", distinct(auditNames))).append(". ");
        }
        if (persistNames.isEmpty() && auditNames.isEmpty()) {
            nar.append("Completes domain processing and returns computed state to caller.");
        }
        story.executiveSummary = nar.toString().trim();

        return story;
    }

    private StepRole classifyRole(int index, int totalSteps, String methodName, String className) {
        if (index == 0) return StepRole.ENTRY;

        String mLower = methodName.toLowerCase(Locale.ROOT);
        String cLower = className.toLowerCase(Locale.ROOT);

        if (mLower.contains("valid") || mLower.contains("check") || mLower.contains("guard") || mLower.contains("verify") || cLower.contains("validator")) {
            return StepRole.VALIDATION;
        }
        if (mLower.contains("audit") || mLower.contains("event") || mLower.contains("publish") || mLower.contains("log") || cLower.contains("audit") || cLower.contains("event")) {
            return StepRole.AUDIT_EVENT;
        }
        if (mLower.contains("dao") || mLower.contains("save") || mLower.contains("insert") || mLower.contains("modify") || mLower.contains("create")
                || cLower.contains("dao") || cLower.contains("repository") || cLower.contains("entity") || cLower.startsWith("pc_")) {
            return StepRole.PERSISTENCE;
        }
        if (cLower.contains("service") || cLower.contains("manager") || cLower.contains("processor") || cLower.contains("coordinator") || cLower.startsWith("bt_") || cLower.endsWith("bt")) {
            return StepRole.BUSINESS_LOGIC;
        }

        return StepRole.PROCESSING;
    }

    private String generateStepAction(StepRole role, String methodName, String className) {
        switch (role) {
            case ENTRY:
                return "Initiates request flow and parses input parameters via " + className + "." + methodName;
            case VALIDATION:
                return "Verifies business constraints and payload integrity in " + className + "." + methodName;
            case BUSINESS_LOGIC:
                return "Executes core domain logic and coordinates state transitions in " + className + "." + methodName;
            case PERSISTENCE:
                return "Commits persistent state to database storage via " + className + "." + methodName;
            case AUDIT_EVENT:
                return "Records compliance audit trail and publishes downstream events via " + className + "." + methodName;
            case PROCESSING:
            default:
                return "Performs intermediate computation and processing in " + className + "." + methodName;
        }
    }

    private String determineStoryCategory(List<String> roleSequence) {
        if (roleSequence.contains("PERSISTENCE")) return "TRANSACTION";
        if (roleSequence.contains("AUDIT_EVENT")) return "AUDIT_FLOW";
        if (roleSequence.contains("VALIDATION")) return "VALIDATED_SERVICE";
        return "DOMAIN_WORKFLOW";
    }

    private String deriveStoryTitle(String className, String methodName) {
        String base = className;
        if (base.endsWith("Controller")) base = base.substring(0, base.length() - 10);
        else if (base.endsWith("Service")) base = base.substring(0, base.length() - 7);
        else if (base.startsWith("BT_")) base = base.substring(3);

        String mClean = methodName;
        if (mClean.contains("(")) mClean = mClean.substring(0, mClean.indexOf('('));

        String splitClass = capitalizeWords(splitCamelCase(base));
        String splitMethod = capitalizeWords(splitCamelCase(mClean));

        if (splitClass.equalsIgnoreCase(splitMethod) || splitMethod.length() <= 3) {
            return splitClass + " Workflow";
        }
        return splitClass + ": " + splitMethod + " Flow";
    }

    private String splitCamelCase(String s) {
        if (s == null || s.isBlank()) return "";
        return s.replaceAll("(?<=[a-z])(?=[A-Z])", " ")
                .replaceAll("_", " ")
                .trim();
    }

    private String capitalizeWords(String str) {
        if (str == null || str.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String word : str.split("\\s+")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                  .append(word.substring(1))
                  .append(" ");
            }
        }
        return sb.toString().trim();
    }

    private List<String> distinct(List<String> list) {
        return new ArrayList<>(new LinkedHashSet<>(list));
    }

    private String extractSimpleName(String fqn) {
        if (fqn == null) return "";
        int paren = fqn.indexOf('(');
        String base = (paren > 0) ? fqn.substring(0, paren) : fqn;
        int dot = base.lastIndexOf('.');
        return (dot >= 0) ? base.substring(dot + 1) : base;
    }

    private String extractClassFqn(String methodFqn) {
        if (methodFqn == null) return "";
        int paren = methodFqn.indexOf('(');
        String base = (paren > 0) ? methodFqn.substring(0, paren) : methodFqn;
        int dot = base.lastIndexOf('.');
        return (dot >= 0) ? base.substring(0, dot) : base;
    }

    private String extractClassSimple(String classFqn) {
        if (classFqn == null) return "";
        int dot = classFqn.lastIndexOf('.');
        return (dot >= 0) ? classFqn.substring(dot + 1) : classFqn;
    }

    public ChangeImpactStory analyzeChangeImpact(String methodFqn,
                                                List<CodeType> types,
                                                List<CodeMethod> methods,
                                                Graph<String, DefaultEdge> graph) {
        ChangeImpactStory story = new ChangeImpactStory();
        story.targetMethod = methodFqn;
        story.targetClass = extractClassFqn(methodFqn);

        if (graph == null || !graph.containsVertex(methodFqn)) {
            story.impactNarrative = "Target method has no recorded call graph dependencies.";
            return story;
        }

        Set<DefaultEdge> inEdges = graph.incomingEdgesOf(methodFqn);
        Set<DefaultEdge> outEdges = graph.outgoingEdgesOf(methodFqn);

        for (DefaultEdge e : inEdges) {
            String src = graph.getEdgeSource(e);
            if (isTestFqn(src)) {
                story.coveringTests.add(src);
            } else {
                story.upstreamCallers.add(src);
            }
        }
        for (DefaultEdge e : outEdges) {
            story.downstreamCallees.add(graph.getEdgeTarget(e));
        }

        List<StorylineSummary> allStorylines = discoverStorylines(types, methods, graph);
        for (StorylineSummary s : allStorylines) {
            Storyline detail = getStorylineByFqn(s.entryPoint, types, methods, graph);
            if (detail != null) {
                boolean affects = detail.steps.stream().anyMatch(st -> st.entityFqn.equals(methodFqn));
                if (affects) {
                    story.affectedStorylines.add(s.title);
                }
            }
        }

        story.totalAffectedWorkflows = story.affectedStorylines.size();
        story.blastRadiusCount = story.upstreamCallers.size() + story.downstreamCallees.size();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Modifying %s.%s directly impacts %d discovered architectural workflows.",
            extractClassSimple(story.targetClass), extractSimpleName(methodFqn), story.totalAffectedWorkflows));
        if (!story.coveringTests.isEmpty()) {
            sb.append(String.format(" %d automated test suites cover this behavior.", story.coveringTests.size()));
        } else {
            sb.append(" Notice: No direct automated tests were found invoking this method in the call graph.");
        }
        if (!story.downstreamCallees.isEmpty()) {
            sb.append(String.format(" Changes propagate downstream to %d called components.", story.downstreamCallees.size()));
        }
        story.impactNarrative = sb.toString();

        return story;
    }

    public TeachMeGuide generateTeachMeGuide(Storyline storyline) {
        TeachMeGuide guide = new TeachMeGuide();
        if (storyline == null) return guide;

        guide.storylineTitle = storyline.title;
        guide.category = storyline.category;
        guide.level1ExecutiveOverview = storyline.executiveSummary;

        LinkedHashSet<String> components = new LinkedHashSet<>();
        List<String> flowSteps = new ArrayList<>();

        for (StorylineStep step : storyline.steps) {
            components.add(String.format("%s [%s]", step.classSimpleName, step.roleLabel));
            flowSteps.add(String.format("Step %d: %s (%s.%s)", step.stepIndex, step.narrativeAction, step.classSimpleName, step.simpleName));
        }

        guide.level2ArchitectureComponents = new ArrayList<>(components);
        guide.level3ExecutionFlow = flowSteps;
        guide.level4CodeDetails = storyline.steps;
        guide.level5Evidence = storyline.evidence;

        return guide;
    }

    private boolean isTestFqn(String fqn) {
        if (fqn == null) return false;
        String lower = fqn.toLowerCase(Locale.ROOT);
        return lower.contains("test") || lower.contains("mock") || lower.contains("spec");
    }
}
