package com.codelens.analysis;

import com.codelens.core.model.CodeMethod;
import com.codelens.core.model.CodeRelationship;
import com.codelens.core.model.CodeType;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Deterministic Change Story Engine.
 * Implements Master Spec Section 7 (Workflow 6): "Code Change Story".
 *
 * <p>Given a diff or set of modified files/lines, maps them to AST methods,
 * evaluates blast radius propagation, pinpoints affected storylines &amp; semantic entities,
 * and recommends covering test classes.</p>
 */
public class ChangeStoryEngine {

    public static class ChangedMethodInfo {
        public String fqn;
        public String simpleName;
        public String classFqn;
        public String classSimpleName;
        public String sourceFile;
        public int startLine;
        public int endLine;
        public int touchPointsCount;
        public List<String> callingServices = new ArrayList<>();
    }

    public static class AffectedWorkflowInfo {
        public String storylineId;
        public String title;
        public String category;
        public String affectedStepName;
        public String affectedStepRole;
        public int stepIndex;
        public int totalSteps;
    }

    public static class ChangeStory {
        public String baseRef;
        public String headRef;
        public int changedMethodsCount;
        public int changedClassesCount;
        public int totalTouchPoints;
        public String riskLevel; // LOW, MEDIUM, HIGH, CRITICAL
        public String narrativeBeforeChange;
        public String narrativeChangeSummary;
        public String narrativeImpact;
        public List<ChangedMethodInfo> changedMethods = new ArrayList<>();
        public List<AffectedWorkflowInfo> affectedStorylines = new ArrayList<>();
        public List<String> affectedEndpoints = new ArrayList<>();
        public List<String> affectedTables = new ArrayList<>();
        public List<String> affectedEvents = new ArrayList<>();
        public List<String> recommendedTests = new ArrayList<>();
        public List<String> reviewChecklist = new ArrayList<>();
    }

    /**
     * Synthesize a complete Change Story from modified file line intervals.
     */
    public ChangeStory synthesizeStory(Map<String, List<int[]>> fileLineRanges,
                                        List<CodeType> types,
                                        List<CodeMethod> methods,
                                        List<CodeRelationship> relationships,
                                        Graph<String, DefaultEdge> callGraph,
                                        StoryEngine storyEngine,
                                        String baseRef,
                                        String headRef) {
        ChangeStory story = new ChangeStory();
        story.baseRef = baseRef != null ? baseRef : "HEAD~1";
        story.headRef = headRef != null ? headRef : "HEAD";

        Map<String, CodeType> typeMap = new HashMap<>();
        for (CodeType t : types) {
            typeMap.put(t.getFqn(), t);
        }

        // 1. Identify changed methods matching file line intervals
        Set<String> changedMethodFqns = new LinkedHashSet<>();
        Set<String> changedClassFqns = new LinkedHashSet<>();

        for (CodeMethod m : methods) {
            String mFile = null;
            if (m.getDeclaringTypeFqn() != null) {
                CodeType decl = typeMap.get(m.getDeclaringTypeFqn());
                if (decl != null) mFile = decl.getSourceFile();
            }
            if (mFile == null) continue;

            for (Map.Entry<String, List<int[]>> entry : fileLineRanges.entrySet()) {
                String diffPath = entry.getKey();
                if (mFile.endsWith(diffPath) || diffPath.endsWith(mFile) || mFile.replace('\\', '/').endsWith(diffPath.replace('\\', '/'))) {
                    for (int[] interval : entry.getValue()) {
                        int mStart = m.getStartLine();
                        int mEnd = Math.max(mStart, m.getEndLine());
                        if (interval[0] <= mEnd && interval[1] >= mStart) {
                            changedMethodFqns.add(m.getFqn());
                            if (m.getDeclaringTypeFqn() != null) {
                                changedClassFqns.add(m.getDeclaringTypeFqn());
                            }
                            break;
                        }
                    }
                }
            }
        }

        // Fallback: If lines didn't hit methods directly (e.g. whole file diff), collect methods of changed classes
        if (changedMethodFqns.isEmpty()) {
            for (CodeType t : types) {
                String tFile = t.getSourceFile();
                if (tFile == null) continue;
                for (String diffPath : fileLineRanges.keySet()) {
                    if (tFile.endsWith(diffPath) || diffPath.endsWith(tFile) || tFile.replace('\\', '/').endsWith(diffPath.replace('\\', '/'))) {
                        changedClassFqns.add(t.getFqn());
                        for (CodeMethod m : methods) {
                            if (t.getFqn().equals(m.getDeclaringTypeFqn())) {
                                changedMethodFqns.add(m.getFqn());
                            }
                        }
                    }
                }
            }
        }

        // 2. Build detailed ChangedMethodInfo and compute upstream touchpoints
        int totalTouchPoints = 0;
        Set<String> allUpstreamCallers = new LinkedHashSet<>();

        for (String mFqn : changedMethodFqns) {
            CodeMethod m = null;
            for (CodeMethod cur : methods) {
                if (cur.getFqn().equals(mFqn)) {
                    m = cur;
                    break;
                }
            }
            if (m == null) continue;

            ChangedMethodInfo info = new ChangedMethodInfo();
            info.fqn = m.getFqn();
            info.simpleName = m.getSimpleName();
            info.classFqn = m.getDeclaringTypeFqn();
            CodeType decl = typeMap.get(m.getDeclaringTypeFqn());
            info.classSimpleName = decl != null ? decl.getSimpleName() : (info.classFqn != null ? info.classFqn.substring(info.classFqn.lastIndexOf('.') + 1) : "");
            info.sourceFile = decl != null && decl.getSourceFile() != null ? decl.getSourceFile() : "";
            info.startLine = m.getStartLine();
            info.endLine = m.getEndLine();

            // Measure callers from callGraph
            if (callGraph != null && callGraph.containsVertex(mFqn)) {
                Set<DefaultEdge> inEdges = callGraph.incomingEdgesOf(mFqn);
                info.touchPointsCount = inEdges.size();
                totalTouchPoints += inEdges.size();
                for (DefaultEdge e : inEdges) {
                    String src = callGraph.getEdgeSource(e);
                    allUpstreamCallers.add(src);
                    String srcLower = src.toLowerCase(Locale.ROOT);
                    if (srcLower.contains("service") || srcLower.contains("manager") || srcLower.contains("engine") || srcLower.contains("handler")) {
                        int dot = src.lastIndexOf('.');
                        info.callingServices.add(dot > 0 ? src.substring(0, dot) : src);
                    }
                }
            }

            story.changedMethods.add(info);
        }

        story.changedMethodsCount = story.changedMethods.size();
        story.changedClassesCount = changedClassFqns.size();
        story.totalTouchPoints = totalTouchPoints;

        // 3. Identify Affected Storylines
        if (storyEngine != null && callGraph != null) {
            List<StoryEngine.StorylineSummary> summaries = storyEngine.discoverStorylines(types, methods, callGraph);
            for (StoryEngine.StorylineSummary s : summaries) {
                StoryEngine.Storyline detail = storyEngine.getStorylineByFqn(s.entryPoint, types, methods, callGraph);
                if (detail != null && detail.steps != null) {
                    for (StoryEngine.StorylineStep step : detail.steps) {
                        if (changedMethodFqns.contains(step.entityFqn) || changedClassFqns.contains(step.classFqn)) {
                            AffectedWorkflowInfo aff = new AffectedWorkflowInfo();
                            aff.storylineId = s.id;
                            aff.title = s.title;
                            aff.category = s.category;
                            aff.affectedStepName = step.classSimpleName + "." + step.simpleName;
                            aff.affectedStepRole = step.role;
                            aff.stepIndex = step.stepIndex;
                            aff.totalSteps = detail.stepCount;
                            story.affectedStorylines.add(aff);
                            break;
                        }
                    }
                }
            }
        }

        // 4. Identify Semantic Endpoints, Tables, and Events
        Set<String> affectedEndpointsSet = new LinkedHashSet<>();
        Set<String> affectedTablesSet = new LinkedHashSet<>();
        Set<String> affectedEventsSet = new LinkedHashSet<>();

        if (relationships != null) {
            for (CodeRelationship r : relationships) {
                if (changedMethodFqns.contains(r.getFromEntityFqn()) || changedClassFqns.contains(r.getFromEntityFqn())) {
                    String to = r.getToEntityFqn();
                    String kind = r.getKind();
                    if (to != null) {
                        if (to.startsWith("endpoint:") || "EXPOSES_ENDPOINT".equalsIgnoreCase(kind) || "HANDLED_BY".equalsIgnoreCase(kind)) {
                            affectedEndpointsSet.add(to.startsWith("endpoint:") ? to.substring(9) : to);
                        } else if (to.startsWith("table:") || "ACCESSES_TABLE".equalsIgnoreCase(kind) || "READS_TABLE".equalsIgnoreCase(kind) || "WRITES_TABLE".equalsIgnoreCase(kind) || "MAPS_TO_TABLE".equalsIgnoreCase(kind)) {
                            affectedTablesSet.add(to.startsWith("table:") ? to.substring(6) : to);
                        } else if (to.startsWith("event:") || "PUBLISHES_EVENT".equalsIgnoreCase(kind) || "LISTENS_EVENT".equalsIgnoreCase(kind)) {
                            affectedEventsSet.add(to.startsWith("event:") ? to.substring(6) : to);
                        }
                    }
                }
            }
        }

        story.affectedEndpoints.addAll(affectedEndpointsSet);
        story.affectedTables.addAll(affectedTablesSet);
        story.affectedEvents.addAll(affectedEventsSet);

        // 5. Discover Recommended Tests
        Set<String> tests = new LinkedHashSet<>();
        for (CodeType t : types) {
            String name = t.getSimpleName();
            boolean isTestClass = name != null && (name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("TestCase") || name.endsWith("IT"));
            if (isTestClass) {
                // Check if test calls or imports changed classes
                for (String cFqn : changedClassFqns) {
                    String simple = cFqn.substring(cFqn.lastIndexOf('.') + 1);
                    if (name.contains(simple)) {
                        tests.add(name);
                    }
                }
                if (callGraph != null) {
                    for (CodeMethod m : methods) {
                        if (t.getFqn().equals(m.getDeclaringTypeFqn()) && callGraph.containsVertex(m.getFqn())) {
                            for (DefaultEdge e : callGraph.outgoingEdgesOf(m.getFqn())) {
                                String target = callGraph.getEdgeTarget(e);
                                if (changedMethodFqns.contains(target)) {
                                    tests.add(name);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        story.recommendedTests.addAll(tests);

        // 6. Risk Level Calculation
        if (story.changedMethodsCount == 0) {
            story.riskLevel = "LOW";
        } else if (totalTouchPoints > 25 || story.affectedTables.size() > 2 || story.changedMethodsCount > 10) {
            story.riskLevel = "CRITICAL";
        } else if (totalTouchPoints > 10 || !story.affectedTables.isEmpty() || story.affectedEndpoints.size() > 2) {
            story.riskLevel = "HIGH";
        } else if (totalTouchPoints > 3 || !story.affectedStorylines.isEmpty()) {
            story.riskLevel = "MEDIUM";
        } else {
            story.riskLevel = "LOW";
        }

        // 7. Structured Narratives Synthesis
        story.narrativeBeforeChange = String.format(
            "Before this change, the codebase contained %d types across %d relationships with %d discovered golden storylines.",
            types.size(), relationships != null ? relationships.size() : 0, storyEngine != null ? story.affectedStorylines.size() : 0
        );

        story.narrativeChangeSummary = String.format(
            "This change modifies %d method(s) across %d class(es) between %s and %s.",
            story.changedMethodsCount, story.changedClassesCount, story.baseRef, story.headRef
        );

        StringBuilder impactBuilder = new StringBuilder();
        impactBuilder.append(String.format("Change propagates to %d direct upstream call site(s). ", totalTouchPoints));
        if (!story.affectedStorylines.isEmpty()) {
            List<String> titles = story.affectedStorylines.stream().map(a -> a.title).distinct().toList();
            impactBuilder.append(String.format("Impacts %d business workflow(s): [%s]. ", titles.size(), String.join(", ", titles)));
        } else {
            impactBuilder.append("No primary business storylines impacted directly. ");
        }
        if (!story.affectedEndpoints.isEmpty()) {
            impactBuilder.append(String.format("Affects %d exposed API endpoint(s). ", story.affectedEndpoints.size()));
        }
        if (!story.affectedTables.isEmpty()) {
            impactBuilder.append(String.format("Interacts with database table(s): %s. ", String.join(", ", story.affectedTables)));
        }
        story.narrativeImpact = impactBuilder.toString().trim();

        // 8. Review Checklist Items
        story.reviewChecklist.add(String.format("Verify behavior of %d modified method(s) against existing unit assertions.", story.changedMethodsCount));
        if (!story.affectedTables.isEmpty()) {
            story.reviewChecklist.add(String.format("Inspect SQL queries & transaction boundaries touching table(s): %s.", String.join(", ", story.affectedTables)));
        }
        if (!story.affectedEndpoints.isEmpty()) {
            story.reviewChecklist.add("Validate API request/response contract backwards-compatibility.");
        }
        if (!story.recommendedTests.isEmpty()) {
            story.reviewChecklist.add(String.format("Execute recommended test suite: %s.", String.join(", ", story.recommendedTests)));
        } else {
            story.reviewChecklist.add("Add unit test coverage for newly introduced method logic.");
        }

        return story;
    }

    /**
     * Formats the ChangeStory as a GitHub Flavored Markdown document.
     * Ready for PR comments, GITHUB_STEP_SUMMARY, or architectural documentation.
     */
    public String toMarkdown(ChangeStory story) {
        if (story == null) return "";
        StringBuilder sb = new StringBuilder();
        String badge = switch (story.riskLevel != null ? story.riskLevel : "LOW") {
            case "CRITICAL" -> "🚨 CRITICAL";
            case "HIGH" -> "🔴 HIGH";
            case "MEDIUM" -> "🟡 MEDIUM";
            default -> "🟢 LOW";
        };

        sb.append(String.format("## 📖 CodeStory PR Change Story: `%s` ➔ `%s`%n%n",
            story.baseRef != null ? story.baseRef : "base",
            story.headRef != null ? story.headRef : "head"));

        sb.append(String.format("> **Risk Level:** %s &nbsp;|&nbsp; **Changed Methods:** %d &nbsp;|&nbsp; **Classes:** %d &nbsp;|&nbsp; **Blast Radius Touchpoints:** %d%n%n",
            badge, story.changedMethodsCount, story.changedClassesCount, story.totalTouchPoints));

        sb.append("### 📝 Change Summary\n");
        sb.append(story.narrativeChangeSummary != null && !story.narrativeChangeSummary.isBlank()
            ? story.narrativeChangeSummary : "No changed methods detected in analyzed interval.").append("\n\n");

        if (story.narrativeBeforeChange != null && !story.narrativeBeforeChange.isBlank()) {
            sb.append("### 🏛️ Architectural Context (Before Change)\n");
            sb.append(story.narrativeBeforeChange).append("\n\n");
        }

        if (story.narrativeImpact != null && !story.narrativeImpact.isBlank()) {
            sb.append("### 💥 Blast Radius & System Impact\n");
            sb.append(story.narrativeImpact).append("\n\n");
        }

        if (story.changedMethods != null && !story.changedMethods.isEmpty()) {
            sb.append(String.format("<details><summary><b>🔍 Changed Methods & Upstream Callers (%d)</b></summary>%n%n", story.changedMethods.size()));
            sb.append("| Method | Class | File:Line | Upstream Callers | Touchpoints |\n");
            sb.append("| :--- | :--- | :--- | :--- | :---: |\n");
            for (var m : story.changedMethods) {
                String callers = (m.callingServices != null && !m.callingServices.isEmpty())
                    ? String.join(", ", m.callingServices) : "*(None)*";
                sb.append(String.format("| `%s` | `%s` | `%s:%d` | %s | %d |%n",
                    m.simpleName, m.classSimpleName, m.sourceFile, m.startLine, callers, m.touchPointsCount));
            }
            sb.append("\n</details>\n\n");
        }

        if (story.affectedStorylines != null && !story.affectedStorylines.isEmpty()) {
            sb.append(String.format("### ⚡ Directly Affected Storylines (%d)%n", story.affectedStorylines.size()));
            for (var a : story.affectedStorylines) {
                sb.append(String.format("- ⚡ **%s** (`%s`) — Step %d/%d: `%s`%n",
                    a.title, a.category, a.stepIndex, a.totalSteps, a.affectedStepName));
            }
            sb.append("\n");
        }

        if (!story.affectedEndpoints.isEmpty() || !story.affectedTables.isEmpty() || !story.affectedEvents.isEmpty()) {
            sb.append("### 🌐 Impacted Semantic Entities\n");
            if (!story.affectedEndpoints.isEmpty()) {
                sb.append("- **Endpoints:** ").append(String.join(", ", story.affectedEndpoints.stream().map(e -> "`" + e + "`").toList())).append("\n");
            }
            if (!story.affectedTables.isEmpty()) {
                sb.append("- **Tables:** ").append(String.join(", ", story.affectedTables.stream().map(t -> "`" + t + "`").toList())).append("\n");
            }
            if (!story.affectedEvents.isEmpty()) {
                sb.append("- **Events:** ").append(String.join(", ", story.affectedEvents.stream().map(e -> "`" + e + "`").toList())).append("\n");
            }
            sb.append("\n");
        }

        if (story.recommendedTests != null && !story.recommendedTests.isEmpty()) {
            sb.append(String.format("### ✅ Recommended Automated Test Suites (%d)%n", story.recommendedTests.size()));
            for (String t : story.recommendedTests) {
                sb.append(String.format("- [ ] `%s`%n", t));
            }
            sb.append("\n");
        }

        if (story.reviewChecklist != null && !story.reviewChecklist.isEmpty()) {
            sb.append("### 📋 Architectural Review Checklist\n");
            for (String ch : story.reviewChecklist) {
                sb.append(String.format("- [ ] %s%n", ch));
            }
            sb.append("\n");
        }

        sb.append("---\n*Generated by [CodeStory](https://github.com/codelens/codelens) — Architectural Intelligence & Impact Story Engine*\n");
        return sb.toString();
    }
}
