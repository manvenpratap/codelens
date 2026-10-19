package com.codelens.analysis;

import com.codelens.core.model.CodeMethod;
import com.codelens.core.model.CodeRelationship;
import com.codelens.core.model.CodeType;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.TarjanSimpleCycles;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import java.util.*;

/**
 * Enterprise Architecture Guardrail Engine.
 * Evaluates architectural invariants, layer decoupling, cyclicity,
 * test coverage on persistent mutations, and PR change blast radius.
 */
public class ArchitectureGuardrailEngine {

    public enum Severity {
        BLOCKER,
        WARNING,
        INFO
    }

    public static class GuardrailViolation {
        public String ruleId;
        public Severity severity;
        public String message;
        public String sourceEntityFqn;
        public String targetEntityFqn;
        public String sourceFile;
        public int sourceLine;

        public GuardrailViolation(String ruleId, Severity severity, String message,
                                  String sourceEntityFqn, String targetEntityFqn,
                                  String sourceFile, int sourceLine) {
            this.ruleId = ruleId;
            this.severity = severity;
            this.message = message;
            this.sourceEntityFqn = sourceEntityFqn;
            this.targetEntityFqn = targetEntityFqn;
            this.sourceFile = sourceFile;
            this.sourceLine = sourceLine;
        }
    }

    public static class GuardrailReport {
        public boolean passed = true;
        public int totalViolations = 0;
        public int blockerCount = 0;
        public int warningCount = 0;
        public List<GuardrailViolation> violations = new ArrayList<>();
        public Map<String, Integer> ruleViolationCounts = new LinkedHashMap<>();
        public List<String> highBlastRadiusMethods = new ArrayList<>();

        public void addViolation(GuardrailViolation v) {
            violations.add(v);
            totalViolations++;
            if (v.severity == Severity.BLOCKER) {
                blockerCount++;
                passed = false;
            } else if (v.severity == Severity.WARNING) {
                warningCount++;
            }
            ruleViolationCounts.merge(v.ruleId, 1, Integer::sum);
        }

        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("## 🛡️ CodeLens Architecture & CI Quality Gate Report\n\n");
            if (passed) {
                sb.append("### ✅ PASSED — Architecture Guardrails Satisfied\n\n");
            } else {
                sb.append("### ❌ FAILED — Architectural Invariants Violated (").append(blockerCount).append(" Blockers)\n\n");
            }

            sb.append("| Metric | Count |\n");
            sb.append("| :--- | :--- |\n");
            sb.append("| Total Violations | **").append(totalViolations).append("** |\n");
            sb.append("| Blocker Issues | **").append(blockerCount).append("** |\n");
            sb.append("| Warnings | **").append(warningCount).append("** |\n\n");

            if (!highBlastRadiusMethods.isEmpty()) {
                sb.append("### ⚠️ Elevated PR Blast Radius Alert\n");
                sb.append("The following modified methods exceed the safe blast-radius threshold:\n");
                for (String m : highBlastRadiusMethods) {
                    sb.append("- `").append(m).append("`\n");
                }
                sb.append("\n");
            }

            if (!violations.isEmpty()) {
                sb.append("### Detailed Violations\n\n");
                sb.append("| Rule | Severity | Entity / Location | Violation Description |\n");
                sb.append("| :--- | :--- | :--- | :--- |\n");
                for (GuardrailViolation v : violations) {
                    String loc = (v.sourceFile != null && !v.sourceFile.isBlank())
                        ? v.sourceFile.substring(Math.max(0, v.sourceFile.lastIndexOf('/') + 1)) + ":" + v.sourceLine
                        : (v.sourceEntityFqn != null ? v.sourceEntityFqn : "-");
                    sb.append("| `").append(v.ruleId).append("` | ")
                      .append(v.severity == Severity.BLOCKER ? "🔴 BLOCKER" : "🟡 WARNING").append(" | `")
                      .append(loc).append("` | ")
                      .append(v.message.replace("|", "\\|")).append(" |\n");
                }
                sb.append("\n");
            }

            return sb.toString();
        }
    }

    public static class GuardrailConfig {
        public boolean failOnLayerViolations = true;
        public boolean failOnCycles = true;
        public boolean failOnUncoveredMutations = false;
        public int maxBlastRadius = 50;

        public GuardrailConfig() {}
    }

    private final GuardrailConfig config;

    public ArchitectureGuardrailEngine() {
        this(new GuardrailConfig());
    }

    public ArchitectureGuardrailEngine(GuardrailConfig config) {
        this.config = config != null ? config : new GuardrailConfig();
    }

    /**
     * Executes all guardrails across the provided codebase entities and graph.
     *
     * @param types list of scanned types
     * @param methods list of scanned methods
     * @param relationships list of scanned relationships
     * @param callGraphAnalyzer analyzer with constructed call graph
     * @param changedMethods optional list of methods modified in a PR (empty or null for full audit)
     */
    public GuardrailReport evaluate(List<CodeType> types,
                                    List<CodeMethod> methods,
                                    List<CodeRelationship> relationships,
                                    CallGraphAnalyzer callGraphAnalyzer,
                                    List<String> changedMethods) {
        GuardrailReport report = new GuardrailReport();
        Map<String, CodeType> typeMap = new HashMap<>(types.size());
        for (CodeType t : types) {
            if (t.getFqn() != null) typeMap.put(t.getFqn(), t);
        }

        Map<String, CodeMethod> methodMap = new HashMap<>(methods.size());
        for (CodeMethod m : methods) {
            if (m.getFqn() != null) methodMap.put(m.getFqn(), m);
        }

        // 1. Layering Guardrail: Controllers/Endpoints must not bypass Service layer to access DAOs/Repositories
        evaluateLayering(relationships, typeMap, methodMap, report);

        // 2. Package Cyclicity Guardrail
        evaluatePackageCycles(types, relationships, report);

        // 3. Persistent Mutation Test Coverage Guardrail
        evaluatePersistentMutationCoverage(methods, callGraphAnalyzer, report);

        // 4. PR Blast Radius Guardrail (if changed methods supplied)
        if (changedMethods != null && !changedMethods.isEmpty() && callGraphAnalyzer != null) {
            evaluateBlastRadius(changedMethods, callGraphAnalyzer, report);
        }

        return report;
    }

    private void evaluateLayering(List<CodeRelationship> relationships,
                                  Map<String, CodeType> typeMap,
                                  Map<String, CodeMethod> methodMap,
                                  GuardrailReport report) {
        for (CodeRelationship rel : relationships) {
            if (!CodeRelationship.KIND_CALLS.equals(rel.getKind())) continue;
            String from = rel.getFromEntityFqn();
            String to = rel.getToEntityFqn();
            if (from == null || to == null) continue;

            String fromClass = extractClassFqn(from);
            String toClass = extractClassFqn(to);

            boolean isController = isControllerClass(fromClass);
            boolean isDataSink = isDataSinkClass(toClass);

            if (isController && isDataSink) {
                CodeMethod mFrom = methodMap.get(from);
                String srcFile = (mFrom != null && typeMap.containsKey(fromClass))
                    ? typeMap.get(fromClass).getSourceFile()
                    : "";
                int line = rel.getSourceLine() > 0 ? rel.getSourceLine()
                    : (mFrom != null ? mFrom.getStartLine() : 1);

                report.addViolation(new GuardrailViolation(
                    "ARCH-LAYER-BYPASS",
                    config.failOnLayerViolations ? Severity.BLOCKER : Severity.WARNING,
                    String.format("Controller '%s' directly accesses Data/Repository '%s' bypassing Service layer.",
                        simpleName(fromClass), simpleName(toClass)),
                    from, to, srcFile, line
                ));
            }
        }
    }

    private void evaluatePackageCycles(List<CodeType> types,
                                       List<CodeRelationship> relationships,
                                       GuardrailReport report) {
        Map<String, String> typeToPkg = new HashMap<>(types.size());
        for (CodeType t : types) {
            if (t.getFqn() != null && t.getPackageFqn() != null) {
                typeToPkg.put(t.getFqn(), t.getPackageFqn());
            }
        }

        Graph<String, DefaultEdge> pkgGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
        for (CodeRelationship rel : relationships) {
            if (!CodeRelationship.KIND_CALLS.equals(rel.getKind()) && !CodeRelationship.KIND_EXTENDS.equals(rel.getKind())) {
                continue;
            }
            String fromClass = extractClassFqn(rel.getFromEntityFqn());
            String toClass = extractClassFqn(rel.getToEntityFqn());
            String pkgFrom = typeToPkg.get(fromClass);
            String pkgTo = typeToPkg.get(toClass);

            if (pkgFrom != null && pkgTo != null && !pkgFrom.equals(pkgTo)) {
                pkgGraph.addVertex(pkgFrom);
                pkgGraph.addVertex(pkgTo);
                pkgGraph.addEdge(pkgFrom, pkgTo);
            }
        }

        List<List<String>> cycles = new TarjanSimpleCycles<>(pkgGraph).findSimpleCycles();
        for (List<String> cycle : cycles) {
            if (cycle.size() >= 2) {
                report.addViolation(new GuardrailViolation(
                    "ARCH-CYCLE-DETECTED",
                    config.failOnCycles ? Severity.BLOCKER : Severity.WARNING,
                    "Circular package dependency tangle: " + String.join(" -> ", cycle) + " -> " + cycle.get(0),
                    cycle.get(0), cycle.get(1), "", 0
                ));
            }
        }
    }

    private void evaluatePersistentMutationCoverage(List<CodeMethod> methods,
                                                    CallGraphAnalyzer callGraphAnalyzer,
                                                    GuardrailReport report) {
        if (callGraphAnalyzer == null) return;

        for (CodeMethod m : methods) {
            String mName = m.getSimpleName();
            if (mName == null) continue;
            boolean isMutation = mName.startsWith("Create") || mName.startsWith("Modify") ||
                                 mName.startsWith("Insert") || mName.startsWith("Update") ||
                                 mName.startsWith("Delete") || mName.startsWith("Save");

            String declClass = m.getDeclaringTypeFqn();
            if (isMutation && declClass != null && !isTestClass(declClass)) {
                List<CallGraphAnalyzer.GraphNode> callers = callGraphAnalyzer.callers(m.getFqn(), 3);
                boolean hasTestCaller = false;
                for (CallGraphAnalyzer.GraphNode caller : callers) {
                    if (isTestClass(caller.id)) {
                        hasTestCaller = true;
                        break;
                    }
                }

                if (!hasTestCaller && callers.size() > 0) {
                    report.addViolation(new GuardrailViolation(
                        "GOV-MUTATION-NO-TEST",
                        config.failOnUncoveredMutations ? Severity.BLOCKER : Severity.WARNING,
                        String.format("State-mutation method '%s.%s' has callers but no automated test coverage in call graph.",
                            simpleName(declClass), mName),
                        m.getFqn(), null, "", m.getStartLine()
                    ));
                }
            }
        }
    }

    private void evaluateBlastRadius(List<String> changedMethods,
                                     CallGraphAnalyzer callGraphAnalyzer,
                                     GuardrailReport report) {
        for (String m : changedMethods) {
            List<CallGraphAnalyzer.GraphNode> callers = callGraphAnalyzer.callers(m, 5);
            if (callers.size() > config.maxBlastRadius) {
                report.highBlastRadiusMethods.add(String.format("%s (impacts %d transitive callers)", m, callers.size()));
                report.addViolation(new GuardrailViolation(
                    "PR-EXCESSIVE-BLAST-RADIUS",
                    Severity.WARNING,
                    String.format("Method '%s' impacts %d upstream callers (threshold: %d). Requires thorough regression testing.",
                        m, callers.size(), config.maxBlastRadius),
                    m, null, "", 0
                ));
            }
        }
    }

    private static boolean isControllerClass(String fqn) {
        if (fqn == null) return false;
        String s = simpleName(fqn);
        return s.endsWith("Controller") || s.endsWith("Endpoint") || s.endsWith("Resource") || s.endsWith("Action");
    }

    private static boolean isDataSinkClass(String fqn) {
        if (fqn == null) return false;
        String s = simpleName(fqn);
        return s.endsWith("Dao") || s.endsWith("DAO") || s.endsWith("Repository") ||
               s.endsWith("Mapper") || s.startsWith("MO_");
    }

    private static boolean isTestClass(String fqn) {
        if (fqn == null) return false;
        String s = simpleName(fqn);
        return s.endsWith("Test") || s.endsWith("Tests") || s.endsWith("TestCase") || s.startsWith("Test");
    }

    private static String extractClassFqn(String methodFqn) {
        if (methodFqn == null) return "";
        int paren = methodFqn.indexOf('(');
        String base = (paren > 0) ? methodFqn.substring(0, paren) : methodFqn;
        int dot = base.lastIndexOf('.');
        return (dot >= 0) ? base.substring(0, dot) : base;
    }

    private static String simpleName(String fqn) {
        if (fqn == null) return "";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
