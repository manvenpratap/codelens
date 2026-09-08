package com.codelens.analysis;

import com.codelens.core.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for generating comprehensive codebase reports in Markdown, HTML, JSON, and CSV.
 * Supported Report Types:
 *   1. Architecture & Dependency Analysis Report
 *   2. Code Quality & Security Audit Report
 *   3. Codebase Inventory & Detailed Metrics
 */
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final ObjectMapper jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final CallGraphAnalyzer callGraph;
    private final FieldImpactAnalyzer fieldImpact;
    private final CodeReviewEngine reviewEngine;

    public ReportService(CallGraphAnalyzer callGraph,
                         FieldImpactAnalyzer fieldImpact,
                         CodeReviewEngine reviewEngine) {
        this.callGraph = callGraph;
        this.fieldImpact = fieldImpact;
        this.reviewEngine = reviewEngine;
    }

    // =========================================================================
    // 1. ARCHITECTURE & DEPENDENCY REPORT
    // =========================================================================

    public static class ArchitectureReportData {
        public String generatedAt;
        public int totalClasses;
        public int totalMethods;
        public int totalFields;
        public int totalDependencies;
        public int totalPackages;
        public int totalCycles;
        public int healthScore; // 0 - 100
        public String healthRating; // A, B, C, D, F
        public List<PackageMetric> packages = new ArrayList<>();
        public List<ClassCouplingMetric> topCoupledClasses = new ArrayList<>();
        public List<List<String>> circularDependencyChains = new ArrayList<>();
        public List<HighBlastRadiusMetric> highBlastRadiusMethods = new ArrayList<>();
    }

    public static class PackageMetric {
        public String packageFqn;
        public int classCount;
        public int afferentCoupling; // Ca (incoming)
        public int efferentCoupling; // Ce (outgoing)
        public double instability;   // I = Ce / (Ca + Ce)
    }

    public static class ClassCouplingMetric {
        public String classFqn;
        public String packageName;
        public int inDegree;
        public int outDegree;
        public int totalCalls;
    }

    public static class HighBlastRadiusMetric {
        public String methodFqn;
        public String affectedField;
        public int readerMethodCount;
    }

    public ArchitectureReportData buildArchitectureData(List<CodeType> types,
                                                        List<CodeMethod> methods,
                                                        List<CodeField> fields,
                                                        List<CodeRelationship> relationships) {
        ArchitectureReportData data = new ArchitectureReportData();
        data.generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        data.totalClasses = types.size();
        data.totalMethods = methods.size();
        data.totalFields = fields.size();
        data.totalDependencies = relationships.size();

        // Group types by package
        Map<String, List<CodeType>> byPkg = types.stream()
            .collect(Collectors.groupingBy(t -> t.getPackageFqn() != null && !t.getPackageFqn().isBlank() ? t.getPackageFqn() : "(default)"));
        data.totalPackages = byPkg.size();

        Map<String, String> typeToPkg = new HashMap<>();
        for (CodeType t : types) {
            typeToPkg.put(t.getFqn(), t.getPackageFqn() != null ? t.getPackageFqn() : "(default)");
        }

        Map<String, Set<String>> pkgIncoming = new HashMap<>();
        Map<String, Set<String>> pkgOutgoing = new HashMap<>();
        for (String pkg : byPkg.keySet()) {
            pkgIncoming.put(pkg, new HashSet<>());
            pkgOutgoing.put(pkg, new HashSet<>());
        }

        // Method -> Class map
        Map<String, String> methodToType = new HashMap<>();
        for (CodeMethod m : methods) {
            methodToType.put(m.getFqn(), m.getDeclaringTypeFqn());
        }

        // Class-level call degrees and graph edges
        Map<String, Integer> classIn = new HashMap<>();
        Map<String, Integer> classOut = new HashMap<>();
        Map<String, Set<String>> classGraph = new HashMap<>();
        for (CodeType t : types) {
            classIn.put(t.getFqn(), 0);
            classOut.put(t.getFqn(), 0);
            classGraph.put(t.getFqn(), new HashSet<>());
        }

        for (CodeRelationship r : relationships) {
            String fromType = methodToType.get(r.getFromEntityFqn());
            String toType = methodToType.get(r.getToEntityFqn());
            if (fromType == null) fromType = r.getFromEntityFqn();
            if (toType == null) toType = r.getToEntityFqn();

            if (fromType != null && toType != null && !fromType.equals(toType)) {
                classOut.put(fromType, classOut.getOrDefault(fromType, 0) + 1);
                classIn.put(toType, classIn.getOrDefault(toType, 0) + 1);
                if (classGraph.containsKey(fromType)) {
                    classGraph.get(fromType).add(toType);
                }

                String fromPkg = typeToPkg.getOrDefault(fromType, "(default)");
                String toPkg = typeToPkg.getOrDefault(toType, "(default)");
                if (!fromPkg.equals(toPkg)) {
                    pkgOutgoing.getOrDefault(fromPkg, new HashSet<>()).add(toPkg);
                    pkgIncoming.getOrDefault(toPkg, new HashSet<>()).add(fromPkg);
                }
            }
        }

        for (Map.Entry<String, List<CodeType>> entry : byPkg.entrySet()) {
            PackageMetric pm = new PackageMetric();
            pm.packageFqn = entry.getKey();
            pm.classCount = entry.getValue().size();
            pm.afferentCoupling = pkgIncoming.getOrDefault(entry.getKey(), Collections.emptySet()).size();
            pm.efferentCoupling = pkgOutgoing.getOrDefault(entry.getKey(), Collections.emptySet()).size();
            int total = pm.afferentCoupling + pm.efferentCoupling;
            pm.instability = total > 0 ? Math.round(((double) pm.efferentCoupling / total) * 100.0) / 100.0 : 0.0;
            data.packages.add(pm);
        }
        data.packages.sort(Comparator.comparing(p -> p.packageFqn));

        // Top coupled classes
        for (CodeType t : types) {
            ClassCouplingMetric cm = new ClassCouplingMetric();
            cm.classFqn = t.getFqn();
            cm.packageName = t.getPackageFqn();
            cm.inDegree = classIn.getOrDefault(t.getFqn(), 0);
            cm.outDegree = classOut.getOrDefault(t.getFqn(), 0);
            cm.totalCalls = cm.inDegree + cm.outDegree;
            data.topCoupledClasses.add(cm);
        }
        data.topCoupledClasses.sort(Comparator.comparingInt((ClassCouplingMetric c) -> c.totalCalls).reversed());
        if (data.topCoupledClasses.size() > 15) {
            data.topCoupledClasses = new ArrayList<>(data.topCoupledClasses.subList(0, 15));
        }

        // Detect circular dependency cycles
        List<List<String>> cycles = findCycles(classGraph);
        data.circularDependencyChains = cycles;
        data.totalCycles = cycles.size();

        // High blast radius methods (fields read by >= 3 methods)
        Map<String, Set<String>> fieldReaders = new HashMap<>();
        Map<String, Set<String>> methodWriters = new HashMap<>();
        for (CodeRelationship r : relationships) {
            if ("READS_FIELD".equals(r.getKind())) {
                fieldReaders.computeIfAbsent(r.getToEntityFqn(), k -> new HashSet<>()).add(r.getFromEntityFqn());
            } else if ("WRITES_FIELD".equals(r.getKind())) {
                methodWriters.computeIfAbsent(r.getFromEntityFqn(), k -> new HashSet<>()).add(r.getToEntityFqn());
            }
        }

        for (Map.Entry<String, Set<String>> entry : methodWriters.entrySet()) {
            String methodFqn = entry.getKey();
            for (String fieldFqn : entry.getValue()) {
                Set<String> readers = fieldReaders.getOrDefault(fieldFqn, Collections.emptySet());
                if (readers.size() >= 3) {
                    HighBlastRadiusMetric hbm = new HighBlastRadiusMetric();
                    hbm.methodFqn = methodFqn;
                    hbm.affectedField = fieldFqn;
                    hbm.readerMethodCount = readers.size();
                    data.highBlastRadiusMethods.add(hbm);
                }
            }
        }
        data.highBlastRadiusMethods.sort(Comparator.comparingInt((HighBlastRadiusMetric h) -> h.readerMethodCount).reversed());
        if (data.highBlastRadiusMethods.size() > 10) {
            data.highBlastRadiusMethods = new ArrayList<>(data.highBlastRadiusMethods.subList(0, 10));
        }

        // Compute overall Health Score
        int score = 100;
        score -= data.totalCycles * 15;
        score -= data.highBlastRadiusMethods.size() * 5;
        if (data.totalClasses > 0) {
            double avgCoupling = (double) data.totalDependencies / data.totalClasses;
            if (avgCoupling > 8) score -= 10;
        }
        score = Math.max(20, Math.min(100, score));
        data.healthScore = score;
        if (score >= 90) data.healthRating = "A+ (Excellent)";
        else if (score >= 80) data.healthRating = "A (Very Good)";
        else if (score >= 70) data.healthRating = "B (Good)";
        else if (score >= 60) data.healthRating = "C (Needs Attention)";
        else data.healthRating = "D (High Coupling & Cycles)";

        return data;
    }

    private static List<List<String>> findCycles(Map<String, Set<String>> graph) {
        List<List<String>> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        List<String> path = new ArrayList<>();

        for (String node : graph.keySet()) {
            if (!visited.contains(node)) {
                dfsCycle(node, graph, visited, inStack, path, cycles);
            }
        }
        return cycles;
    }

    private static void dfsCycle(String u, Map<String, Set<String>> graph,
                                 Set<String> visited, Set<String> inStack,
                                 List<String> path, List<List<String>> cycles) {
        visited.add(u);
        inStack.add(u);
        path.add(u);

        for (String v : graph.getOrDefault(u, Collections.emptySet())) {
            if (!visited.contains(v)) {
                dfsCycle(v, graph, visited, inStack, path, cycles);
            } else if (inStack.contains(v)) {
                int startIdx = path.indexOf(v);
                if (startIdx >= 0) {
                    List<String> cycle = new ArrayList<>(path.subList(startIdx, path.size()));
                    if (cycles.size() < 10) {
                        cycles.add(cycle);
                    }
                }
            }
        }

        path.remove(path.size() - 1);
        inStack.remove(u);
    }

    public String renderArchitectureMarkdown(ArchitectureReportData d) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 🏛️ CodeLens Architecture & Dependency Report\n\n");
        sb.append("> **Generated**: `").append(d.generatedAt).append("` | **Health Score**: `")
          .append(d.healthScore).append("/100 (").append(d.healthRating).append(")`\n\n");

        sb.append("## 1. Executive Summary\n\n");
        sb.append("| Metric | Count |\n");
        sb.append("| :--- | :--- |\n");
        sb.append("| **Total Packages / Modules** | ").append(d.totalPackages).append(" |\n");
        sb.append("| **Total Types / Classes** | ").append(d.totalClasses).append(" |\n");
        sb.append("| **Total Methods** | ").append(d.totalMethods).append(" |\n");
        sb.append("| **Total Fields** | ").append(d.totalFields).append(" |\n");
        sb.append("| **Inter-Entity Dependencies** | ").append(d.totalDependencies).append(" |\n");
        sb.append("| **Circular Dependency Cycles** | ").append(d.totalCycles == 0 ? "✅ 0 (Acyclic)" : "⚠️ " + d.totalCycles).append(" |\n\n");

        sb.append("## 2. Package Architecture & Instability\n\n");
        sb.append("The **Instability ($I = \\frac{Ce}{Ca + Ce}$)** metric ranges from `0.0` (maximally stable, relied upon by others) to `1.0` (maximally flexible, depends on others).\n\n");
        sb.append("| Package | Classes | Afferent ($Ca$) | Efferent ($Ce$) | Instability ($I$) |\n");
        sb.append("| :--- | :---: | :---: | :---: | :---: |\n");
        for (PackageMetric pm : d.packages) {
            sb.append("| `").append(pm.packageFqn).append("` | ").append(pm.classCount)
              .append(" | ").append(pm.afferentCoupling).append(" | ").append(pm.efferentCoupling)
              .append(" | `").append(String.format("%.2f", pm.instability)).append("` |\n");
        }
        sb.append("\n");

        sb.append("## 3. Most Highly Coupled Classes\n\n");
        sb.append("| Class | In-Degree (Callers) | Out-Degree (Callees) | Total Coupling |\n");
        sb.append("| :--- | :---: | :---: | :---: |\n");
        for (ClassCouplingMetric cm : d.topCoupledClasses) {
            sb.append("| `").append(cm.classFqn).append("` | ").append(cm.inDegree)
              .append(" | ").append(cm.outDegree).append(" | **").append(cm.totalCalls).append("** |\n");
        }
        sb.append("\n");

        sb.append("## 4. Circular Dependency Analysis\n\n");
        if (d.circularDependencyChains.isEmpty()) {
            sb.append("✅ **No circular dependency cycles detected.** The system topology conforms to the Acyclic Dependencies Principle (ADP).\n\n");
        } else {
            sb.append("⚠️ **").append(d.circularDependencyChains.size()).append(" Circular Cycle(s) Detected:**\n\n");
            for (int i = 0; i < d.circularDependencyChains.size(); i++) {
                sb.append("- **Cycle ").append(i + 1).append("**: `")
                  .append(String.join(" ➔ ", d.circularDependencyChains.get(i)))
                  .append(" ➔ ").append(d.circularDependencyChains.get(i).get(0)).append("`\n");
            }
            sb.append("\n");
        }

        if (!d.highBlastRadiusMethods.isEmpty()) {
            sb.append("## 5. High Blast-Radius Method Modifications\n\n");
            sb.append("| Method | Modified Field | Impacted Reader Methods |\n");
            sb.append("| :--- | :--- | :---: |\n");
            for (HighBlastRadiusMetric hbm : d.highBlastRadiusMethods) {
                sb.append("| `").append(hbm.methodFqn).append("` | `").append(hbm.affectedField)
                  .append("` | **").append(hbm.readerMethodCount).append(" readers** |\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    public String renderArchitectureHtml(ArchitectureReportData d) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\" />\n");
        sb.append("<title>CodeLens Architecture Report</title>\n");
        sb.append("<style>\n");
        sb.append(":root { --bg: #0b0f19; --surface: #131b2e; --border: #1e293b; --text: #f1f5f9; --muted: #94a3b8; --accent: #3b82f6; --green: #10b981; --red: #ef4444; --yellow: #f59e0b; }\n");
        sb.append("@media print { body { background: #fff !important; color: #000 !important; } .card { border: 1px solid #ccc !important; background: #fff !important; } }\n");
        sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: var(--bg); color: var(--text); padding: 40px 20px; max-width: 1000px; margin: 0 auto; line-height: 1.6; }\n");
        sb.append("h1, h2, h3 { color: #fff; margin-top: 24px; }\n");
        sb.append(".header { border-bottom: 1px solid var(--border); padding-bottom: 20px; margin-bottom: 30px; display: flex; justify-content: space-between; align-items: flex-end; }\n");
        sb.append(".grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; margin: 20px 0; }\n");
        sb.append(".card { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 16px; }\n");
        sb.append(".card-val { font-size: 28px; font-weight: 700; font-family: monospace; color: var(--accent); }\n");
        sb.append(".card-lbl { font-size: 12px; text-transform: uppercase; color: var(--muted); letter-spacing: 0.5px; }\n");
        sb.append("table { width: 100%; border-collapse: collapse; margin: 16px 0; font-size: 13px; }\n");
        sb.append("th, td { padding: 10px 14px; text-align: left; border-bottom: 1px solid var(--border); }\n");
        sb.append("th { background: var(--surface); color: var(--muted); font-size: 11px; text-transform: uppercase; }\n");
        sb.append("code { font-family: ui-monospace, SFMono-Regular, monospace; font-size: 12px; background: rgba(255,255,255,0.06); padding: 2px 6px; border-radius: 4px; }\n");
        sb.append(".badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 11px; font-weight: 600; }\n");
        sb.append(".badge-green { background: rgba(16,185,129,0.15); color: var(--green); }\n");
        sb.append(".badge-red { background: rgba(239,68,68,0.15); color: var(--red); }\n");
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<div class=\"header\"><div><h1>🏛️ CodeLens Architecture & Dependency Report</h1>");
        sb.append("<p style=\"color:var(--muted); margin:4px 0;\">Generated on <strong>").append(d.generatedAt).append("</strong></p></div>");
        sb.append("<div><span class=\"badge ").append(d.healthScore >= 75 ? "badge-green" : "badge-red")
          .append("\" style=\"font-size:16px; padding:6px 14px;\">Health Score: ").append(d.healthScore).append("/100</span></div></div>\n");

        sb.append("<div class=\"grid\">");
        sb.append("<div class=\"card\"><div class=\"card-val\">").append(d.totalPackages).append("</div><div class=\"card-lbl\">Packages / Modules</div></div>");
        sb.append("<div class=\"card\"><div class=\"card-val\">").append(d.totalClasses).append("</div><div class=\"card-lbl\">Classes / Types</div></div>");
        sb.append("<div class=\"card\"><div class=\"card-val\">").append(d.totalMethods).append("</div><div class=\"card-lbl\">Total Methods</div></div>");
        sb.append("<div class=\"card\"><div class=\"card-val\">").append(d.totalDependencies).append("</div><div class=\"card-lbl\">Inter-Class Calls</div></div>");
        sb.append("<div class=\"card\"><div class=\"card-val\" style=\"color:").append(d.totalCycles == 0 ? "var(--green)" : "var(--red)").append(";\">")
          .append(d.totalCycles).append("</div><div class=\"card-lbl\">Cycles Detected</div></div>");
        sb.append("</div>\n");

        sb.append("<h2>Package Architecture & Instability</h2>\n");
        sb.append("<table><thead><tr><th>Package</th><th>Classes</th><th>Afferent ($Ca$)</th><th>Efferent ($Ce$)</th><th>Instability ($I$)</th></tr></thead><tbody>\n");
        for (PackageMetric pm : d.packages) {
            sb.append("<tr><td><code>").append(escapeHtml(pm.packageFqn)).append("</code></td><td>").append(pm.classCount)
              .append("</td><td>").append(pm.afferentCoupling).append("</td><td>").append(pm.efferentCoupling)
              .append("</td><td><code>").append(String.format("%.2f", pm.instability)).append("</code></td></tr>\n");
        }
        sb.append("</tbody></table>\n");

        sb.append("<h2>Most Highly Coupled Classes</h2>\n");
        sb.append("<table><thead><tr><th>Class</th><th>In-Degree</th><th>Out-Degree</th><th>Total Coupling</th></tr></thead><tbody>\n");
        for (ClassCouplingMetric cm : d.topCoupledClasses) {
            sb.append("<tr><td><code>").append(escapeHtml(cm.classFqn)).append("</code></td><td>").append(cm.inDegree)
              .append("</td><td>").append(cm.outDegree).append("</td><td><strong>").append(cm.totalCalls).append("</strong></td></tr>\n");
        }
        sb.append("</tbody></table>\n");

        sb.append("<h2>Circular Dependencies</h2>\n");
        if (d.circularDependencyChains.isEmpty()) {
            sb.append("<p><span class=\"badge badge-green\">✔ 0 Cycles</span> The codebase strictly satisfies the Acyclic Dependencies Principle.</p>\n");
        } else {
            sb.append("<ul>\n");
            for (List<String> cycle : d.circularDependencyChains) {
                sb.append("<li><span class=\"badge badge-red\">Cycle</span> <code>")
                  .append(escapeHtml(String.join(" ➔ ", cycle)))
                  .append(" ➔ ").append(escapeHtml(cycle.get(0))).append("</code></li>\n");
            }
            sb.append("</ul>\n");
        }

        sb.append("</body>\n</html>");
        return sb.toString();
    }

    public String renderArchitectureJson(ArchitectureReportData d) {
        try {
            return jsonMapper.writeValueAsString(d);
        } catch (Exception e) {
            log.error("Failed to render architecture JSON: {}", e.getMessage());
            return "{}";
        }
    }

    // =========================================================================
    // 2. CODE QUALITY & REVIEW AUDIT REPORT
    // =========================================================================

    public static class ReviewReportData {
        public String generatedAt;
        public int totalFilesReviewed;
        public int totalFindings;
        public int criticalCount;
        public int warningCount;
        public int infoCount;
        public Map<String, Integer> categoryCounts = new HashMap<>();
        public List<ReviewFinding> findings = new ArrayList<>();
    }

    public ReviewReportData buildReviewReportData(List<CodeType> types) {
        ReviewReportData data = new ReviewReportData();
        data.generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // Gather all distinct source files
        Set<String> sourceFiles = types.stream()
            .map(CodeType::getSourceFile)
            .filter(Objects::nonNull)
            .filter(f -> !f.isBlank())
            .collect(Collectors.toSet());

        data.totalFilesReviewed = sourceFiles.size();
        List<ReviewFinding> allFindings = new ArrayList<>();

        for (String file : sourceFiles) {
            try {
                List<ReviewFinding> fileFindings = reviewEngine.reviewFile(file, callGraph, fieldImpact);
                allFindings.addAll(fileFindings);
            } catch (Exception e) {
                log.warn("Error reviewing {}: {}", file, e.getMessage());
            }
        }

        data.findings = allFindings;
        data.totalFindings = allFindings.size();

        for (ReviewFinding f : allFindings) {
            String sev = f.getSeverity() != null ? f.getSeverity().toUpperCase() : "INFO";
            if ("CRITICAL".equals(sev)) data.criticalCount++;
            else if ("WARNING".equals(sev)) data.warningCount++;
            else data.infoCount++;

            String cat = f.getCategory() != null ? f.getCategory() : "Other";
            data.categoryCounts.put(cat, data.categoryCounts.getOrDefault(cat, 0) + 1);
        }

        return data;
    }

    public String renderReviewMarkdown(ReviewReportData d) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 🛡️ CodeLens Code Quality & Security Audit Report\n\n");
        sb.append("> **Generated**: `").append(d.generatedAt).append("` | **Files Reviewed**: `")
          .append(d.totalFilesReviewed).append("` | **Total Findings**: `").append(d.totalFindings).append("`\n\n");

        sb.append("## 1. Executive Summary\n\n");
        sb.append("| Severity | Finding Count |\n");
        sb.append("| :--- | :--- |\n");
        sb.append("| 🔴 **CRITICAL** | ").append(d.criticalCount).append(" |\n");
        sb.append("| 🟡 **WARNING** | ").append(d.warningCount).append(" |\n");
        sb.append("| 🔵 **INFO** | ").append(d.infoCount).append(" |\n\n");

        sb.append("## 2. Breakdown by Category\n\n");
        sb.append("| Category | Findings |\n");
        sb.append("| :--- | :--- |\n");
        for (Map.Entry<String, Integer> entry : d.categoryCounts.entrySet()) {
            sb.append("| **").append(entry.getKey()).append("** | ").append(entry.getValue()).append(" |\n");
        }
        sb.append("\n");

        sb.append("## 3. Detailed Findings & Recommendations\n\n");
        if (d.findings.isEmpty()) {
            sb.append("🎉 **Clean codebase! Zero review violations found.**\n\n");
        } else {
            for (int i = 0; i < d.findings.size(); i++) {
                ReviewFinding f = d.findings.get(i);
                String sev = f.getSeverity() != null ? f.getSeverity().toUpperCase() : "INFO";
                String sevIcon = "CRITICAL".equals(sev) ? "🔴"
                               : "WARNING".equals(sev) ? "🟡" : "🔵";
                sb.append("### ").append(i + 1).append(". [").append(f.getCheckName()).append("] ")
                  .append(sevIcon).append(" ").append(f.getMessage()).append("\n\n");
                sb.append("- **Location**: `").append(f.getEntityFqn()).append(f.getLine() > 0 ? ":" + f.getLine() : "").append("`\n");
                sb.append("- **Category**: ").append(f.getCategory()).append("\n");
                if (f.getSuggestion() != null && !f.getSuggestion().isBlank()) {
                    sb.append("- **Recommendation**: *").append(f.getSuggestion()).append("*\n");
                }
                if (f.getSourceSnippet() != null && !f.getSourceSnippet().isBlank()) {
                    sb.append("\n```java\n").append(f.getSourceSnippet()).append("\n```\n");
                }
                sb.append("\n---\n\n");
            }
        }

        return sb.toString();
    }

    public String renderReviewHtml(ReviewReportData d) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\" />\n");
        sb.append("<title>CodeLens Code Review Audit Report</title>\n");
        sb.append("<style>\n");
        sb.append(":root { --bg: #0b0f19; --surface: #131b2e; --border: #1e293b; --text: #f1f5f9; --muted: #94a3b8; --accent: #3b82f6; --green: #10b981; --red: #ef4444; --yellow: #f59e0b; }\n");
        sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: var(--bg); color: var(--text); padding: 40px 20px; max-width: 1000px; margin: 0 auto; line-height: 1.6; }\n");
        sb.append(".header { border-bottom: 1px solid var(--border); padding-bottom: 20px; margin-bottom: 30px; display: flex; justify-content: space-between; align-items: flex-end; }\n");
        sb.append(".grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; margin: 20px 0; }\n");
        sb.append(".card { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 16px; }\n");
        sb.append(".card-val { font-size: 28px; font-weight: 700; font-family: monospace; }\n");
        sb.append(".card-lbl { font-size: 12px; text-transform: uppercase; color: var(--muted); letter-spacing: 0.5px; }\n");
        sb.append(".finding-item { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n");
        sb.append(".finding-title { font-size: 15px; font-weight: 600; display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }\n");
        sb.append(".badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 11px; font-weight: 700; }\n");
        sb.append(".badge-crit { background: rgba(239,68,68,0.18); color: #f87171; border: 1px solid rgba(239,68,68,0.4); }\n");
        sb.append(".badge-warn { background: rgba(245,158,11,0.18); color: #fbbf24; border: 1px solid rgba(245,158,11,0.4); }\n");
        sb.append(".badge-info { background: rgba(59,130,246,0.18); color: #60a5fa; border: 1px solid rgba(59,130,246,0.4); }\n");
        sb.append("code { font-family: ui-monospace, SFMono-Regular, monospace; font-size: 12px; background: rgba(255,255,255,0.06); padding: 2px 6px; border-radius: 4px; }\n");
        sb.append("pre { background: rgba(0,0,0,0.3); border: 1px solid var(--border); padding: 12px; border-radius: 6px; overflow-x: auto; font-family: monospace; font-size: 12px; }\n");
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<div class=\"header\"><div><h1>🛡️ CodeLens Code Quality Audit Report</h1>");
        sb.append("<p style=\"color:var(--muted); margin:4px 0;\">Reviewed <strong>").append(d.totalFilesReviewed)
          .append(" files</strong> on ").append(d.generatedAt).append("</p></div>");
        sb.append("<div><span class=\"badge badge-info\" style=\"font-size:16px; padding:6px 14px;\">")
          .append(d.totalFindings).append(" Total Findings</span></div></div>\n");

        sb.append("<div class=\"grid\">");
        sb.append("<div class=\"card\"><div class=\"card-val\" style=\"color:#f87171;\">").append(d.criticalCount).append("</div><div class=\"card-lbl\">Critical Defects</div></div>");
        sb.append("<div class=\"card\"><div class=\"card-val\" style=\"color:#fbbf24;\">").append(d.warningCount).append("</div><div class=\"card-lbl\">Warnings</div></div>");
        sb.append("<div class=\"card\"><div class=\"card-val\" style=\"color:#60a5fa;\">").append(d.infoCount).append("</div><div class=\"card-lbl\">Suggestions / Info</div></div>");
        sb.append("</div>\n");

        sb.append("<h2>Findings & Remediation Guide</h2>\n");
        for (ReviewFinding f : d.findings) {
            String sev = f.getSeverity() != null ? f.getSeverity().toUpperCase() : "INFO";
            String badgeCls = "CRITICAL".equals(sev) ? "badge-crit"
                            : "WARNING".equals(sev) ? "badge-warn" : "badge-info";
            sb.append("<div class=\"finding-item\">");
            sb.append("<div class=\"finding-title\"><span class=\"badge ").append(badgeCls).append("\">")
              .append(sev).append("</span> <span>[").append(f.getCheckName()).append("] ")
              .append(escapeHtml(f.getMessage())).append("</span></div>");
            sb.append("<div style=\"font-size:12px; color:var(--muted); margin-bottom:8px;\">Entity: <code>")
              .append(escapeHtml(f.getEntityFqn())).append(f.getLine() > 0 ? ":" + f.getLine() : "").append("</code> &bull; Category: <strong>")
              .append(escapeHtml(f.getCategory())).append("</strong></div>");
            if (f.getSuggestion() != null && !f.getSuggestion().isBlank()) {
                sb.append("<div style=\"font-size:13px; color:#34d399; margin-bottom:8px;\">💡 <em>")
                  .append(escapeHtml(f.getSuggestion())).append("</em></div>");
            }
            if (f.getSourceSnippet() != null && !f.getSourceSnippet().isBlank()) {
                sb.append("<pre>").append(escapeHtml(f.getSourceSnippet())).append("</pre>");
            }
            sb.append("</div>\n");
        }

        sb.append("</body>\n</html>");
        return sb.toString();
    }

    public String renderReviewCsv(ReviewReportData d) {
        StringBuilder sb = new StringBuilder();
        sb.append("CheckName,Severity,Category,EntityFqn,LineNumber,Message,Suggestion\n");
        for (ReviewFinding f : d.findings) {
            sb.append(escapeCsv(f.getCheckName())).append(",")
              .append(escapeCsv(f.getSeverity())).append(",")
              .append(escapeCsv(f.getCategory())).append(",")
              .append(escapeCsv(f.getEntityFqn())).append(",")
              .append(f.getLine()).append(",")
              .append(escapeCsv(f.getMessage())).append(",")
              .append(escapeCsv(f.getSuggestion())).append("\n");
        }
        return sb.toString();
    }

    public String renderReviewJson(ReviewReportData d) {
        try {
            return jsonMapper.writeValueAsString(d);
        } catch (Exception e) {
            log.error("Failed to render review JSON: {}", e.getMessage());
            return "{}";
        }
    }

    // =========================================================================
    // 3. CODEBASE INVENTORY & METRICS REPORT
    // =========================================================================

    public static class MetricsReportData {
        public String generatedAt;
        public int totalTypes;
        public int totalMethods;
        public int totalFields;
        public int totalLines;
        public List<TypeMetricRow> types = new ArrayList<>();
    }

    public static class TypeMetricRow {
        public String fqn;
        public String simpleName;
        public String packageName;
        public String kind;
        public int lineCount;
        public int methodCount;
        public int fieldCount;
        public String sourceFile;
    }

    public MetricsReportData buildMetricsData(List<CodeType> types, List<CodeMethod> methods, List<CodeField> fields) {
        MetricsReportData d = new MetricsReportData();
        d.generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        d.totalTypes = types.size();
        d.totalMethods = methods.size();
        d.totalFields = fields.size();
        d.totalLines = types.stream().mapToInt(CodeType::getLineCount).sum();

        for (CodeType t : types) {
            TypeMetricRow row = new TypeMetricRow();
            row.fqn = t.getFqn();
            row.simpleName = t.getSimpleName();
            row.packageName = t.getPackageFqn();
            row.kind = t.getKind();
            row.lineCount = t.getLineCount();
            row.methodCount = t.getMethodCount();
            row.fieldCount = t.getFieldCount();
            row.sourceFile = t.getSourceFile();
            d.types.add(row);
        }
        d.types.sort(Comparator.comparing(t -> t.fqn));
        return d;
    }

    public String renderMetricsCsv(MetricsReportData d) {
        StringBuilder sb = new StringBuilder();
        sb.append("FQN,SimpleName,Package,Kind,LineCount,MethodCount,FieldCount,SourceFile\n");
        for (TypeMetricRow r : d.types) {
            sb.append(escapeCsv(r.fqn)).append(",")
              .append(escapeCsv(r.simpleName)).append(",")
              .append(escapeCsv(r.packageName)).append(",")
              .append(escapeCsv(r.kind)).append(",")
              .append(r.lineCount).append(",")
              .append(r.methodCount).append(",")
              .append(r.fieldCount).append(",")
              .append(escapeCsv(r.sourceFile)).append("\n");
        }
        return sb.toString();
    }

    public String renderMetricsMarkdown(MetricsReportData d) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 📊 CodeLens Codebase Inventory & Metrics\n\n");
        sb.append("> **Generated**: `").append(d.generatedAt).append("` | **Total Classes**: `")
          .append(d.totalTypes).append("` | **Total Lines**: `").append(d.totalLines).append("`\n\n");

        sb.append("| Class / Type | Package | Kind | Lines | Methods | Fields |\n");
        sb.append("| :--- | :--- | :---: | :---: | :---: | :---: |\n");
        for (TypeMetricRow r : d.types) {
            sb.append("| `").append(r.simpleName).append("` | `").append(r.packageName).append("` | ")
              .append(r.kind).append(" | ").append(r.lineCount).append(" | ")
              .append(r.methodCount).append(" | ").append(r.fieldCount).append(" |\n");
        }
        return sb.toString();
    }

    public String renderMetricsHtml(MetricsReportData d) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\" />\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n");
        sb.append("<title>CodeLens Codebase Inventory & Metrics Report</title>\n");
        sb.append("<style>\n");
        sb.append(":root { --bg: #0b0f19; --surface: #131b2e; --border: #1e293b; --text: #f1f5f9; --muted: #94a3b8; --accent: #3b82f6; --green: #10b981; --purple: #a855f7; --yellow: #f59e0b; }\n");
        sb.append("@media print { body { background: #fff !important; color: #000 !important; } .card { border: 1px solid #ccc !important; background: #fff !important; } }\n");
        sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: var(--bg); color: var(--text); padding: 40px 20px; max-width: 1100px; margin: 0 auto; line-height: 1.6; }\n");
        sb.append("h1, h2, h3 { color: #fff; margin-top: 24px; }\n");
        sb.append(".header { border-bottom: 1px solid var(--border); padding-bottom: 20px; margin-bottom: 30px; display: flex; justify-content: space-between; align-items: flex-end; }\n");
        sb.append(".grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; margin: 20px 0; }\n");
        sb.append(".card { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 16px; }\n");
        sb.append(".card-val { font-size: 28px; font-weight: 700; font-family: monospace; color: var(--accent); }\n");
        sb.append(".card-lbl { font-size: 12px; text-transform: uppercase; color: var(--muted); letter-spacing: 0.5px; }\n");
        sb.append("table { width: 100%; border-collapse: collapse; margin: 16px 0; font-size: 13px; }\n");
        sb.append("th, td { padding: 10px 14px; text-align: left; border-bottom: 1px solid var(--border); }\n");
        sb.append("th { background: var(--surface); color: var(--muted); font-size: 11px; text-transform: uppercase; }\n");
        sb.append("code { font-family: ui-monospace, SFMono-Regular, monospace; font-size: 12px; background: rgba(255,255,255,0.06); padding: 2px 6px; border-radius: 4px; }\n");
        sb.append(".badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 11px; font-weight: 600; text-transform: uppercase; }\n");
        sb.append(".badge-class { background: rgba(59,130,246,0.15); color: #60a5fa; }\n");
        sb.append(".badge-interface { background: rgba(16,185,129,0.15); color: #34d399; }\n");
        sb.append(".badge-enum { background: rgba(245,158,11,0.15); color: #fbbf24; }\n");
        sb.append(".badge-record { background: rgba(168,85,247,0.15); color: #c084fc; }\n");
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<div class=\"header\"><div><h1>📊 CodeLens Codebase Inventory & Metrics</h1>");
        sb.append("<p style=\"color:var(--muted); margin:4px 0;\">Generated on <strong>").append(d.generatedAt).append("</strong></p></div>");
        sb.append("<div><span class=\"badge badge-class\" style=\"font-size:15px; padding:6px 14px;\">")
          .append(d.totalTypes).append(" Types Indexed</span></div></div>\n");

        sb.append("<div class=\"grid\">");
        sb.append("<div class=\"card\"><div class=\"card-val\">").append(d.totalTypes).append("</div><div class=\"card-lbl\">Total Types / Classes</div></div>");
        sb.append("<div class=\"card\"><div class=\"card-val\" style=\"color:var(--green);\">").append(d.totalMethods).append("</div><div class=\"card-lbl\">Total Methods</div></div>");
        sb.append("<div class=\"card\"><div class=\"card-val\" style=\"color:var(--yellow);\">").append(d.totalFields).append("</div><div class=\"card-lbl\">Total Fields</div></div>");
        sb.append("<div class=\"card\"><div class=\"card-val\" style=\"color:var(--purple);\">").append(d.totalLines).append("</div><div class=\"card-lbl\">Lines of Code</div></div>");
        sb.append("</div>\n");

        sb.append("<h2>Indexed Type Inventory</h2>\n");
        sb.append("<table><thead><tr><th>Class / Type</th><th>Package</th><th>Kind</th><th style=\"text-align:right;\">Lines</th><th style=\"text-align:right;\">Methods</th><th style=\"text-align:right;\">Fields</th></tr></thead><tbody>\n");
        for (TypeMetricRow r : d.types) {
            String kindClass = "badge-class";
            if ("INTERFACE".equalsIgnoreCase(r.kind)) kindClass = "badge-interface";
            else if ("ENUM".equalsIgnoreCase(r.kind)) kindClass = "badge-enum";
            else if ("RECORD".equalsIgnoreCase(r.kind)) kindClass = "badge-record";

            sb.append("<tr>");
            sb.append("<td><code>").append(escapeHtml(r.simpleName)).append("</code></td>");
            sb.append("<td><code>").append(escapeHtml(r.packageName)).append("</code></td>");
            sb.append("<td><span class=\"badge ").append(kindClass).append("\">").append(escapeHtml(r.kind)).append("</span></td>");
            sb.append("<td style=\"text-align:right; font-family:monospace;\">").append(r.lineCount).append("</td>");
            sb.append("<td style=\"text-align:right; font-family:monospace;\">").append(r.methodCount).append("</td>");
            sb.append("<td style=\"text-align:right; font-family:monospace;\">").append(r.fieldCount).append("</td>");
            sb.append("</tr>\n");
        }
        sb.append("</tbody></table>\n");

        sb.append("</body>\n</html>");
        return sb.toString();
    }

    public String renderMetricsJson(MetricsReportData d) {
        try {
            return jsonMapper.writeValueAsString(d);
        } catch (Exception e) {
            log.error("Failed to render metrics JSON: {}", e.getMessage());
            return "{}";
        }
    }

    // =========================================================================
    // 4. STANDALONE INTERACTIVE HTML GRAPH SNAPSHOT
    // =========================================================================

    public String generateInteractiveHtmlSnapshot(String projectName, Object fullGraphData, Object archGraphData, ArchitectureReportData archData) {
        String fullGraphJson = "{}";
        String archGraphJson = "{}";
        String archDataJson = "{}";
        try {
            if (fullGraphData != null) fullGraphJson = jsonMapper.writeValueAsString(fullGraphData);
            if (archGraphData != null) archGraphJson = jsonMapper.writeValueAsString(archGraphData);
            if (archData != null) archDataJson = jsonMapper.writeValueAsString(archData);
        } catch (Exception e) {
            log.error("Failed to serialize graph snapshot data: {}", e.getMessage());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\" />\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n");
        sb.append("<title>Interactive Graph Snapshot - ").append(escapeHtml(projectName)).append("</title>\n");
        sb.append("<style>\n");
        sb.append(":root {\n");
        sb.append("  --bg-primary: #07090e; --bg-surface: #0f172a; --bg-card: #1e293b; --border: #334155;\n");
        sb.append("  --text-main: #f8fafc; --text-muted: #94a3b8; --accent: #10b981; --accent-hover: #059669;\n");
        sb.append("  --cyan: #06b6d4; --blue: #3b82f6; --purple: #a855f7; --red: #ef4444; --orange: #f97316;\n");
        sb.append("}\n");
        sb.append("* { box-sizing: border-box; margin: 0; padding: 0; }\n");
        sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background: var(--bg-primary); color: var(--text-main); height: 100vh; display: flex; flex-direction: column; overflow: hidden; }\n");
        sb.append("header { height: 54px; background: var(--bg-surface); border-bottom: 1px solid var(--border); display: flex; align-items: center; justify-content: space-between; padding: 0 16px; z-index: 100; gap: 12px; }\n");
        sb.append(".header-left { display: flex; align-items: center; gap: 10px; min-width: 0; flex-shrink: 0; }\n");
        sb.append(".logo { font-size: 15px; font-weight: 800; color: var(--accent); display: flex; align-items: center; gap: 6px; letter-spacing: 0.5px; white-space: nowrap; }\n");
        sb.append(".project-badge { background: rgba(16,185,129,0.12); color: #34d399; border: 1px solid rgba(16,185,129,0.3); padding: 3px 10px; border-radius: 6px; font-size: 12px; font-weight: 600; font-family: monospace; white-space: nowrap; max-width: 180px; overflow: hidden; text-overflow: ellipsis; }\n");
        sb.append(".header-center { display: flex; align-items: center; gap: 12px; flex: 1; justify-content: center; min-width: 0; }\n");
        sb.append(".search-box { position: relative; width: 280px; flex-shrink: 1; min-width: 100px; }\n");
        sb.append(".search-box input { width: 100%; background: #07090e; border: 1px solid var(--border); border-radius: 6px; padding: 6px 30px 6px 30px; color: var(--text-main); font-size: 12px; outline: none; transition: border-color 0.2s; }\n");
        sb.append(".search-box input:focus { border-color: var(--accent); box-shadow: 0 0 0 2px rgba(16,185,129,0.15); }\n");
        sb.append(".search-box input::placeholder { color: var(--text-muted); }\n");
        sb.append(".search-box .icon { position: absolute; left: 9px; top: 50%; transform: translateY(-50%); font-size: 12px; color: var(--text-muted); pointer-events: none; }\n");
        sb.append(".search-clear { position: absolute; right: 8px; top: 50%; transform: translateY(-50%); background: none; border: none; color: var(--text-muted); cursor: pointer; font-size: 14px; padding: 0; display: none; line-height: 1; }\n");
        sb.append(".search-clear.visible { display: block; }\n");
        sb.append(".search-clear:hover { color: var(--text-main); }\n");
        sb.append(".scope-toggle { display: flex; background: #07090e; border: 1px solid var(--border); border-radius: 6px; padding: 2px; flex-shrink: 0; }\n");
        sb.append(".scope-btn { background: transparent; border: none; color: var(--text-muted); padding: 5px 14px; font-size: 12px; font-weight: 600; border-radius: 4px; cursor: pointer; transition: all 0.2s; white-space: nowrap; }\n");
        sb.append(".scope-btn.active { background: var(--accent); color: #000; font-weight: 700; }\n");
        sb.append(".scope-btn:not(.active):hover { color: var(--text-main); background: rgba(255,255,255,0.06); }\n");
        sb.append(".header-right { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--text-muted); flex-shrink: 0; }\n");
        sb.append(".stats-pill { background: rgba(99,102,241,0.12); border: 1px solid rgba(99,102,241,0.3); color: #a5b4fc; padding: 2px 10px; border-radius: 12px; font-size: 11px; font-weight: 600; font-family: monospace; white-space: nowrap; }\n");
        sb.append(".sidebar-toggle-btn { background: none; border: 1px solid var(--border); color: var(--text-muted); border-radius: 6px; padding: 5px 10px; font-size: 12px; cursor: pointer; transition: all 0.15s; display: flex; align-items: center; gap: 5px; font-weight: 600; white-space: nowrap; }\n");
        sb.append(".sidebar-toggle-btn:hover { border-color: var(--accent); color: var(--text-main); }\n");
        sb.append(".main-content { flex: 1; position: relative; display: flex; overflow: hidden; }\n");
        sb.append(".canvas-wrap { flex: 1; position: relative; overflow: hidden; min-width: 0; }\n");
        sb.append("#graph-canvas { position: absolute; top: 0; left: 0; width: 100%; height: 100%; cursor: grab; }\n");
        sb.append("#graph-canvas:active { cursor: grabbing; }\n");
        sb.append(".sidebar { width: 320px; background: var(--bg-surface); border-left: 1px solid var(--border); display: flex; flex-direction: column; z-index: 50; transition: width 0.25s ease, opacity 0.25s ease; overflow: hidden; flex-shrink: 0; }\n");
        sb.append(".sidebar.collapsed { width: 0; opacity: 0; border-left-width: 0; }\n");
        sb.append(".sidebar-header { padding: 12px 16px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-shrink: 0; }\n");
        sb.append(".sidebar-title { font-size: 13px; font-weight: 700; white-space: nowrap; }\n");
        sb.append(".sidebar-body { flex: 1; overflow-y: auto; padding: 14px; }\n");
        sb.append(".sidebar-body::-webkit-scrollbar { width: 4px; } .sidebar-body::-webkit-scrollbar-track { background: transparent; } .sidebar-body::-webkit-scrollbar-thumb { background: #334155; border-radius: 4px; }\n");
        sb.append(".entity-card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 14px; margin-bottom: 12px; }\n");
        sb.append(".entity-title { font-size: 12px; font-weight: 700; word-break: break-all; margin-bottom: 6px; font-family: monospace; color: #67e8f9; line-height: 1.4; }\n");
        sb.append(".entity-badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 10px; font-weight: 700; text-transform: uppercase; margin-bottom: 8px; }\n");
        sb.append(".badge-class { background: rgba(59,130,246,0.2); color: #60a5fa; border: 1px solid rgba(59,130,246,0.4); }\n");
        sb.append(".badge-method { background: rgba(16,185,129,0.2); color: #34d399; border: 1px solid rgba(16,185,129,0.4); }\n");
        sb.append(".badge-fetch { background: rgba(6,182,212,0.2); color: #22d3ee; border: 1px solid rgba(6,182,212,0.4); }\n");
        sb.append(".badge-mutate { background: rgba(249,115,22,0.2); color: #fb923c; border: 1px solid rgba(249,115,22,0.4); }\n");
        sb.append(".badge-batch { background: rgba(168,85,247,0.2); color: #c084fc; border: 1px solid rgba(168,85,247,0.4); }\n");
        sb.append(".badge-grabber { background: rgba(236,72,153,0.2); color: #f472b6; border: 1px solid rgba(236,72,153,0.4); }\n");
        sb.append(".prop-row { display: flex; justify-content: space-between; align-items: baseline; font-size: 12px; margin: 5px 0; gap: 8px; }\n");
        sb.append(".prop-name { color: var(--text-muted); flex-shrink: 0; }\n");
        sb.append(".prop-val { font-weight: 600; font-family: monospace; color: #e2e8f0; text-align: right; word-break: break-all; }\n");
        sb.append(".connections-list { list-style: none; margin-top: 10px; max-height: 200px; overflow-y: auto; }\n");
        sb.append(".connections-list::-webkit-scrollbar { width: 3px; } .connections-list::-webkit-scrollbar-thumb { background: #334155; border-radius: 3px; }\n");
        sb.append(".connection-item { font-size: 11px; font-family: monospace; padding: 5px 8px; border-radius: 4px; margin-bottom: 3px; background: rgba(255,255,255,0.03); display: flex; align-items: center; justify-content: space-between; cursor: pointer; gap: 6px; }\n");
        sb.append(".connection-item:hover { background: rgba(255,255,255,0.09); }\n");
        sb.append(".conn-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }\n");
        sb.append(".conn-pkg { color: var(--text-muted); font-size: 10px; flex-shrink: 0; max-width: 90px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }\n");
        sb.append(".no-selection { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; gap: 12px; color: var(--text-muted); text-align: center; padding: 24px; }\n");
        sb.append(".no-selection-icon { font-size: 36px; opacity: 0.3; }\n");
        sb.append(".no-selection-text { font-size: 13px; line-height: 1.5; max-width: 200px; }\n");
        sb.append(".hud-controls { position: absolute; bottom: 18px; left: 16px; display: flex; gap: 6px; z-index: 40; flex-wrap: wrap; max-width: 420px; }\n");
        sb.append(".hud-btn { background: rgba(15,23,42,0.92); backdrop-filter: blur(8px); border: 1px solid var(--border); color: var(--text-main); border-radius: 8px; padding: 7px 13px; font-size: 12px; font-weight: 600; cursor: pointer; display: flex; align-items: center; gap: 6px; transition: all 0.15s; box-shadow: 0 2px 8px rgba(0,0,0,0.4); white-space: nowrap; }\n");
        sb.append(".hud-btn:hover { background: rgba(30,41,59,0.97); border-color: var(--accent); }\n");
        sb.append(".hud-btn.active { background: var(--accent); color: #000; font-weight: 700; border-color: var(--accent); }\n");
        sb.append(".legend-panel { position: absolute; top: 14px; left: 14px; width: 216px; background: rgba(15,23,42,0.92); backdrop-filter: blur(10px); border: 1px solid var(--border); border-radius: 10px; font-size: 11px; z-index: 40; box-shadow: 0 4px 20px rgba(0,0,0,0.5); display: flex; flex-direction: column; overflow: hidden; }\n");
        sb.append(".legend-header { display: flex; align-items: center; justify-content: space-between; padding: 9px 12px 8px; border-bottom: 1px solid rgba(51,65,85,0.5); cursor: pointer; flex-shrink: 0; user-select: none; }\n");
        sb.append(".legend-title { font-weight: 700; color: var(--text-muted); text-transform: uppercase; font-size: 10px; letter-spacing: 0.7px; }\n");
        sb.append(".legend-collapse-btn { background: none; border: none; color: var(--text-muted); cursor: pointer; font-size: 11px; padding: 0; line-height: 1; transition: transform 0.2s; }\n");
        sb.append(".legend-panel.collapsed .legend-collapse-btn { transform: rotate(180deg); }\n");
        sb.append(".legend-body { overflow-y: auto; padding: 8px 10px; max-height: 300px; }\n");
        sb.append(".legend-body::-webkit-scrollbar { width: 3px; } .legend-body::-webkit-scrollbar-thumb { background: #334155; border-radius: 3px; }\n");
        sb.append(".legend-panel.collapsed .legend-body { display: none; }\n");
        sb.append(".legend-item { display: flex; align-items: center; gap: 7px; margin: 3px 0; padding: 2px 0; }\n");
        sb.append(".legend-dot { width: 9px; height: 9px; border-radius: 50%; flex-shrink: 0; }\n");
        sb.append(".legend-name { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: #cbd5e1; }\n");
        sb.append(".floating-tooltip { position: fixed; pointer-events: none; background: rgba(15,23,42,0.97); border: 1px solid var(--border); border-radius: 8px; padding: 10px 14px; font-size: 12px; z-index: 1000; display: none; box-shadow: 0 4px 24px rgba(0,0,0,0.6); max-width: 280px; }\n");
        sb.append(".loading-overlay { position: fixed; inset: 0; background: #07090e; display: flex; flex-direction: column; align-items: center; justify-content: center; z-index: 9999; gap: 18px; transition: opacity 0.5s ease; }\n");
        sb.append(".loading-overlay.hidden { opacity: 0; pointer-events: none; }\n");
        sb.append(".spinner { width: 44px; height: 44px; border: 3px solid rgba(16,185,129,0.2); border-top-color: #10b981; border-radius: 50%; animation: spin 0.8s linear infinite; }\n");
        sb.append("@keyframes spin { to { transform: rotate(360deg); } }\n");
        sb.append(".loading-text { color: var(--text-muted); font-size: 14px; font-weight: 500; }\n");
        sb.append(".loading-sub { color: #475569; font-size: 12px; }\n");
        sb.append("@media (max-width: 900px) { .header-right .gen-date { display: none; } .search-box { width: 180px; } .sidebar { width: 280px; } .project-badge { max-width: 120px; } }\n");
        sb.append("@media (max-width: 640px) { .search-box { display: none; } .sidebar { position: absolute; right: 0; height: 100%; } header { padding: 0 10px; gap: 8px; } }\n");
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<div class=\"loading-overlay\" id=\"loading-overlay\">\n");
        sb.append("  <div class=\"spinner\"></div>\n");
        sb.append("  <div class=\"loading-text\">Building graph…</div>\n");
        sb.append("  <div class=\"loading-sub\" id=\"loading-sub\">Parsing graph data</div>\n");
        sb.append("</div>\n");

        sb.append("<header>\n");
        sb.append("  <div class=\"header-left\">\n");
        sb.append("    <span class=\"logo\">⬡ CODELENS</span>\n");
        sb.append("    <span style=\"font-size:12px;color:var(--text-muted);font-weight:600;white-space:nowrap;\">Interactive Graph Snapshot</span>\n");
        sb.append("    <span class=\"project-badge\" title=\"").append(escapeHtml(projectName)).append("\">").append(escapeHtml(projectName)).append("</span>\n");
        sb.append("  </div>\n");
        sb.append("  <div class=\"header-center\">\n");
        sb.append("    <div class=\"search-box\">\n");
        sb.append("      <span class=\"icon\">🔍</span>\n");
        sb.append("      <input id=\"search-input\" type=\"text\" placeholder=\"Filter classes / methods…\" autocomplete=\"off\" />\n");
        sb.append("      <button class=\"search-clear\" id=\"search-clear\" title=\"Clear search\">✕</button>\n");
        sb.append("    </div>\n");
        sb.append("    <div class=\"scope-toggle\">\n");
        sb.append("      <button id=\"btn-scope-arch\" class=\"scope-btn active\">📦 Classes</button>\n");
        sb.append("      <button id=\"btn-scope-methods\" class=\"scope-btn\">⚙ Methods</button>\n");
        sb.append("    </div>\n");
        sb.append("  </div>\n");
        sb.append("  <div class=\"header-right\">\n");
        sb.append("    <span class=\"stats-pill\" id=\"header-stats\">—</span>\n");
        sb.append("    <span class=\"gen-date\" style=\"color:var(--text-muted); font-size:11px;\">Generated ").append(escapeHtml(archData != null ? archData.generatedAt : "Today")).append("</span>\n");
        sb.append("    <button class=\"sidebar-toggle-btn\" id=\"btn-sidebar-toggle\" title=\"Toggle entity inspector\">☰ Inspector</button>\n");
        sb.append("  </div>\n");
        sb.append("</header>\n");

        sb.append("<div class=\"main-content\">\n");
        sb.append("  <div class=\"canvas-wrap\" id=\"canvas-wrap\">\n");
        sb.append("    <canvas id=\"graph-canvas\"></canvas>\n");

        sb.append("    <div class=\"legend-panel\" id=\"legend-panel\">\n");
        sb.append("      <div class=\"legend-header\" id=\"legend-header\">\n");
        sb.append("        <span class=\"legend-title\">Packages</span>\n");
        sb.append("        <button class=\"legend-collapse-btn\" id=\"legend-collapse-btn\" title=\"Collapse legend\">▲</button>\n");
        sb.append("      </div>\n");
        sb.append("      <div class=\"legend-body\" id=\"legend-items\"></div>\n");
        sb.append("    </div>\n");

        sb.append("    <div class=\"hud-controls\">\n");
        sb.append("      <button class=\"hud-btn\" id=\"btn-fit\" title=\"Fit all nodes in view\">⛶ Fit View</button>\n");
        sb.append("      <button class=\"hud-btn\" id=\"btn-pause\" title=\"Pause / resume physics simulation\">⏸ Pause</button>\n");
        sb.append("      <button class=\"hud-btn\" id=\"btn-pojo\" title=\"Toggle POJO accessor methods\">⚡ Hide POJOs</button>\n");
        sb.append("    </div>\n");
        sb.append("  </div>\n");

        sb.append("  <div class=\"sidebar\" id=\"inspector-sidebar\">\n");
        sb.append("    <div class=\"sidebar-header\">\n");
        sb.append("      <span class=\"sidebar-title\">Entity Inspector</span>\n");
        sb.append("      <span id=\"inspector-type-badge\" class=\"entity-badge badge-class\" style=\"display:none;\">CLASS</span>\n");
        sb.append("    </div>\n");
        sb.append("    <div class=\"sidebar-body\" id=\"sidebar-body\">\n");
        sb.append("      <div class=\"no-selection\" id=\"no-selection-hint\">\n");
        sb.append("        <div class=\"no-selection-icon\">🔎</div>\n");
        sb.append("        <div class=\"no-selection-text\">Click any node on the graph to inspect it</div>\n");
        sb.append("      </div>\n");
        sb.append("      <div id=\"inspector-content\" style=\"display:none;\">\n");
        sb.append("        <div class=\"entity-card\">\n");
        sb.append("          <div class=\"entity-title\" id=\"inspector-name\"></div>\n");
        sb.append("          <div id=\"inspector-archetype\"></div>\n");
        sb.append("          <div class=\"prop-row\"><span class=\"prop-name\">Package</span><span class=\"prop-val\" id=\"inspector-pkg\">-</span></div>\n");
        sb.append("          <div class=\"prop-row\"><span class=\"prop-name\">Incoming</span><span class=\"prop-val\" id=\"inspector-indegree\">0</span></div>\n");
        sb.append("          <div class=\"prop-row\"><span class=\"prop-name\">Outgoing</span><span class=\"prop-val\" id=\"inspector-outdegree\">0</span></div>\n");
        sb.append("        </div>\n");
        sb.append("        <div style=\"font-size:11px; font-weight:700; margin-bottom:8px; color:var(--text-muted); text-transform:uppercase; letter-spacing:0.5px;\">Connections</div>\n");
        sb.append("        <ul class=\"connections-list\" id=\"inspector-connections\"></ul>\n");
        sb.append("      </div>\n");
        sb.append("    </div>\n");
        sb.append("  </div>\n");

        sb.append("  <div class=\"floating-tooltip\" id=\"graph-tooltip\"></div>\n");
        sb.append("</div>\n");

        sb.append("<script id=\"codelens-fullgraph\" type=\"application/json\">\n").append(fullGraphJson.replace("</script>", "<\\/script>")).append("\n</script>\n");
        sb.append("<script id=\"codelens-archgraph\" type=\"application/json\">\n").append(archGraphJson.replace("</script>", "<\\/script>")).append("\n</script>\n");
        sb.append("<script id=\"codelens-archdata\" type=\"application/json\">\n").append(archDataJson.replace("</script>", "<\\/script>")).append("\n</script>\n");

        // Embedded interactive HTML5 canvas force graph viewer script
        sb.append("<script>\n");
        sb.append("(function() {\n");
        sb.append("  const fullGraph = JSON.parse(document.getElementById('codelens-fullgraph').textContent || '{}');\n");
        sb.append("  const archGraph = JSON.parse(document.getElementById('codelens-archgraph').textContent || '{}');\n");
        sb.append("  let currentScope = 'arch';\n");
        sb.append("  let hidePojos = false;\n");
        sb.append("  let physicsRunning = true;\n");
        sb.append("  let searchQuery = '';\n");
        sb.append("  let activeNode = null;\n");
        sb.append("  let hoveredNode = null;\n");
        sb.append("  let zoom = 1;\n");
        sb.append("  let panX = 0, panY = 0;\n");
        sb.append("  let isDragging = false, dragStartX = 0, dragStartY = 0;\n");
        sb.append("  let draggedNode = null;\n");
        sb.append("  let nodes = [], edges = [], nodeMap = new Map();\n");
        sb.append("  const canvas = document.getElementById('graph-canvas');\n");
        sb.append("  const ctx = canvas.getContext('2d');\n");
        sb.append("  const tooltip = document.getElementById('graph-tooltip');\n");
        sb.append("  const loadingOverlay = document.getElementById('loading-overlay');\n");
        sb.append("  const loadingSub = document.getElementById('loading-sub');\n");
        sb.append("  const GRAPHIFY_COLORS = [\n");
        sb.append("    '#3b82f6', '#10b981', '#f59e0b', '#ec4899', '#8b5cf6', '#06b6d4', '#f97316', '#14b8a6',\n");
        sb.append("    '#6366f1', '#84cc16', '#e11d48', '#0ea5e9', '#d946ef', '#eab308', '#a855f7', '#22c55e',\n");
        sb.append("    '#f43f5e', '#38bdf8', '#4ade80', '#fb923c', '#c084fc', '#2dd4bf', '#f472b6', '#a3e635',\n");
        sb.append("    '#818cf8', '#fbbf24', '#f87171', '#67e8f9', '#34d399', '#fca5a5', '#93c5fd', '#fde047',\n");
        sb.append("    '#c4b5fd', '#5eead4', '#fda4af', '#bef264', '#a5b4fc', '#fcd34d', '#7dd3fc', '#6ee7b7',\n");
        sb.append("    '#f9a8d4', '#d8b4fe', '#99f6e4', '#fbcfe8', '#fed7aa', '#e2e8f0', '#94a3b8', '#64748b'\n");
        sb.append("  ];\n");
        sb.append("  const pkgColorMap = new Map();\n");
        sb.append("  let colorIdx = 0;\n");
        sb.append("  function getEntityColor(str) {\n");
        sb.append("    if (!str) return '#94a3b8';\n");
        sb.append("    if (!pkgColorMap.has(str)) {\n");
        sb.append("      pkgColorMap.set(str, GRAPHIFY_COLORS[colorIdx % GRAPHIFY_COLORS.length]);\n");
        sb.append("      colorIdx++;\n");
        sb.append("    }\n");
        sb.append("    return pkgColorMap.get(str);\n");
        sb.append("  }\n");
        sb.append("  function extractClassFqn(fqn, type) {\n");
        sb.append("    if (!fqn) return '';\n");
        sb.append("    const clean = fqn.split('(')[0].trim();\n");
        sb.append("    const parts = clean.split('.');\n");
        sb.append("    if (parts.length <= 1) return clean;\n");
        sb.append("    if (type === 'CLASS' || type === 'PACKAGE' || type === 'MODULE') return clean;\n");
        sb.append("    if (type === 'METHOD' || type === 'FIELD' || fqn.includes('(')) return parts.slice(0, -1).join('.');\n");
        sb.append("    if (/^[a-z_]/.test(parts[parts.length - 1])) return parts.slice(0, -1).join('.');\n");
        sb.append("    return clean;\n");
        sb.append("  }\n");
        sb.append("  function getClassColor(fqn, type) { return getEntityColor(extractClassFqn(fqn, type)); }\n");
        sb.append("  function extractModule(pkgOrFqn) {\n");
        sb.append("    if (!pkgOrFqn) return '';\n");
        sb.append("    const parts = pkgOrFqn.split('.');\n");
        sb.append("    for (let i = parts.length - 1; i >= 0; i--) {\n");
        sb.append("      const p = parts[i];\n");
        sb.append("      if (p.length >= 2 && p.length <= 4 && /^[A-Z0-9]+$/.test(p)) return p;\n");
        sb.append("    }\n");
        sb.append("    return '';\n");
        sb.append("  }\n");
        sb.append("  function classify(name, fqn, pkg) {\n");
        sb.append("    const n = name || '';\n");
        sb.append("    const mod = extractModule(pkg || fqn);\n");
        sb.append("    if (mod) {\n");
        sb.append("      if (n.startsWith(mod + 'ET')) return { badge: 'FETCH', text: '📥 Elementary Transaction', color: '#06b6d4', cls: 'badge-fetch' };\n");
        sb.append("      if (n.startsWith(mod + 'BT')) return { badge: 'MUTATE', text: '⚡ Business Transaction', color: '#f97316', cls: 'badge-mutate' };\n");
        sb.append("      if (n.startsWith(mod + 'PS')) return { badge: 'BATCH', text: '⚙️ Batch Processor', color: '#a855f7', cls: 'badge-batch' };\n");
        sb.append("      if (n.startsWith(mod + 'PB')) return { badge: 'PRE-BATCH', text: '⏮️ Process Before Batch', color: '#3b82f6', cls: 'badge-class' };\n");
        sb.append("      if (n.startsWith(mod + 'PA')) return { badge: 'POST-BATCH', text: '⏭️ Process After Batch', color: '#10b981', cls: 'badge-method' };\n");
        sb.append("      if (n.startsWith(mod + 'DG')) return { badge: 'DATA-GRABBER', text: '📊 Data Grabber', color: '#ec4899', cls: 'badge-grabber' };\n");
        sb.append("    }\n");
        sb.append("    if (n.startsWith('get') || n.startsWith('set') || n.startsWith('is')) return { badge: 'POJO', text: 'POJO Accessor', color: '#64748b', isPojo: true };\n");
        sb.append("    return null;\n");
        sb.append("  }\n");
        sb.append("  let simAlpha = 1.0;\n");
        sb.append("  function extractPackage(rn) {\n");
        sb.append("    if (rn.packageFqn && rn.packageFqn !== 'default' && rn.packageFqn !== '(default)') {\n");
        sb.append("      return rn.packageFqn;\n");
        sb.append("    }\n");
        sb.append("    if (currentScope === 'arch' || rn.type === 'MODULE' || rn.type === 'PACKAGE' || !rn.id.includes('.')) {\n");
        sb.append("      return rn.label || rn.id;\n");
        sb.append("    }\n");
        sb.append("    const clean = (rn.id || '').split('(')[0];\n");
        sb.append("    const parts = clean.split('.');\n");
        sb.append("    if (parts.length >= 3) return parts.slice(0, -2).join('.');\n");
        sb.append("    if (parts.length >= 2) return parts[0];\n");
        sb.append("    return rn.label || rn.id || 'default';\n");
        sb.append("  }\n");
        sb.append("  function updateStats() {\n");
        sb.append("    const visible = nodes.filter(n => !hidePojos || !n.isPojo);\n");
        sb.append("    const pkgSet = new Set(visible.map(n => n.pkg));\n");
        sb.append("    const label = currentScope === 'arch'\n");
        sb.append("      ? `${visible.length} components · ${pkgSet.size} modules`\n");
        sb.append("      : `${visible.length} methods · ${pkgSet.size} pkgs`;\n");
        sb.append("    const el = document.getElementById('header-stats');\n");
        sb.append("    if (el) el.textContent = label;\n");
        sb.append("  }\n");
        sb.append("  function loadGraphData() {\n");
        sb.append("    pkgColorMap.clear();\n");
        sb.append("    colorIdx = 0;\n");
        sb.append("    activeNode = null;\n");
        sb.append("    simAlpha = 1.0;\n");
        sb.append("    const raw = currentScope === 'arch' ? archGraph : fullGraph;\n");
        sb.append("    const rawNodes = raw.nodes || [];\n");
        sb.append("    const rawEdges = raw.edges || [];\n");
        sb.append("    nodeMap.clear();\n");
        sb.append("    nodes = [];\n");
        sb.append("    const pkgs = new Set();\n");
        sb.append("    rawNodes.forEach((rn) => {\n");
        sb.append("      const pkg = extractPackage(rn);\n");
        sb.append("      pkgs.add(pkg);\n");
        sb.append("      const arch = classify(rn.label || rn.id, rn.id, pkg);\n");
        sb.append("      const nodeType = rn.type || (currentScope === 'arch' ? 'CLASS' : 'METHOD');\n");
        sb.append("      const nodeColor = getEntityColor(pkg);\n");
        sb.append("      const node = {\n");
        sb.append("        id: rn.id,\n");
        sb.append("        label: rn.label || rn.id.split('.').pop(),\n");
        sb.append("        type: nodeType,\n");
        sb.append("        pkg,\n");
        sb.append("        color: nodeColor,\n");
        sb.append("        arch,\n");
        sb.append("        isPojo: arch && arch.isPojo,\n");
        sb.append("        x: (rn.x !== undefined && rn.x !== null) ? rn.x : (Math.random() - 0.5) * 800,\n");
        sb.append("        y: (rn.y !== undefined && rn.y !== null) ? rn.y : (Math.random() - 0.5) * 600,\n");
        sb.append("        vx: 0, vy: 0,\n");
        sb.append("        radius: currentScope === 'arch' ? 9 : 4,\n");
        sb.append("        inDegree: 0, outDegree: 0,\n");
        sb.append("        neighbors: []\n");
        sb.append("      };\n");
        sb.append("      nodes.push(node);\n");
        sb.append("      nodeMap.set(node.id, node);\n");
        sb.append("    });\n");
        sb.append("    const precomputedCount = rawNodes.filter(rn => rn.x !== undefined && rn.x !== null).length;\n");
        sb.append("    if (rawNodes.length > 0 && precomputedCount / rawNodes.length >= 0.7) {\n");
        sb.append("      physicsRunning = false;\n");
        sb.append("      const pBtn = document.getElementById('btn-pause');\n");
        sb.append("      if (pBtn) { pBtn.textContent = '▶ Run Physics'; pBtn.classList.remove('active'); }\n");
        sb.append("    } else {\n");
        sb.append("      physicsRunning = true;\n");
        sb.append("      const pBtn = document.getElementById('btn-pause');\n");
        sb.append("      if (pBtn) { pBtn.textContent = '⏸ Pause'; pBtn.classList.add('active'); }\n");
        sb.append("    }\n");
        sb.append("    edges = [];\n");
        sb.append("    rawEdges.forEach(re => {\n");
        sb.append("      const src = nodeMap.get(re.source);\n");
        sb.append("      const tgt = nodeMap.get(re.target);\n");
        sb.append("      if (src && tgt && src !== tgt) {\n");
        sb.append("        src.outDegree++;\n");
        sb.append("        tgt.inDegree++;\n");
        sb.append("        src.neighbors.push({ node: tgt, kind: 'calls' });\n");
        sb.append("        tgt.neighbors.push({ node: src, kind: 'calledBy' });\n");
        sb.append("        edges.push({ source: src, target: tgt });\n");
        sb.append("      }\n");
        sb.append("    });\n");
        sb.append("    renderLegend(pkgs);\n");
        sb.append("    updateStats();\n");
        sb.append("    resetInspector();\n");
        sb.append("    resizeCanvas();\n");
        sb.append("    fitView();\n");
        sb.append("  }\n");
        sb.append("  function renderLegend(pkgs) {\n");
        sb.append("    const wrap = document.getElementById('legend-items');\n");
        sb.append("    if (!wrap) return;\n");
        sb.append("    wrap.innerHTML = '';\n");
        sb.append("    const arr = Array.from(pkgs).slice(0, 40);\n");
        sb.append("    arr.forEach(pkg => {\n");
        sb.append("      const row = document.createElement('div');\n");
        sb.append("      row.className = 'legend-item';\n");
        sb.append("      const shortName = pkg.split('.').pop() || pkg;\n");
        sb.append("      row.innerHTML = `<span class=\"legend-dot\" style=\"background:${getEntityColor(pkg)}\"></span><span class=\"legend-name\" title=\"${pkg}\">${shortName}</span>`;\n");
        sb.append("      row.addEventListener('click', () => {\n");
        sb.append("        const searchEl = document.getElementById('search-input');\n");
        sb.append("        if (searchEl) { searchEl.value = shortName; searchQuery = shortName.toLowerCase(); updateSearchClear(); }\n");
        sb.append("      });\n");
        sb.append("      wrap.appendChild(row);\n");
        sb.append("    });\n");
        sb.append("  }\n");
        sb.append("  function fitView() {\n");
        sb.append("    if (nodes.length === 0) return;\n");
        sb.append("    const dpr = window.devicePixelRatio || 1;\n");
        sb.append("    const W = canvas.width / dpr;\n");
        sb.append("    const H = canvas.height / dpr;\n");
        sb.append("    if (W < 10 || H < 10) return;\n");
        sb.append("    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;\n");
        sb.append("    nodes.forEach(n => {\n");
        sb.append("      if (hidePojos && n.isPojo) return;\n");
        sb.append("      if (n.x < minX) minX = n.x;\n");
        sb.append("      if (n.x > maxX) maxX = n.x;\n");
        sb.append("      if (n.y < minY) minY = n.y;\n");
        sb.append("      if (n.y > maxY) maxY = n.y;\n");
        sb.append("    });\n");
        sb.append("    if (minX === Infinity || minX === maxX) { minX = -300; maxX = 300; minY = -200; maxY = 200; }\n");
        sb.append("    const pad = 60;\n");
        sb.append("    const boxW = Math.max(maxX - minX + pad * 2, 200);\n");
        sb.append("    const boxH = Math.max(maxY - minY + pad * 2, 200);\n");
        sb.append("    zoom = Math.max(0.05, Math.min(2.0, Math.min(W / boxW, H / boxH) * 0.92));\n");
        sb.append("    panX = W / 2 - ((minX + maxX) / 2) * zoom;\n");
        sb.append("    panY = H / 2 - ((minY + maxY) / 2) * zoom;\n");
        sb.append("  }\n");
        sb.append("  function simulate() {\n");
        sb.append("    if (!physicsRunning) return;\n");
        sb.append("    const alpha = simAlpha;\n");
        sb.append("    simAlpha = Math.max(0.2, simAlpha * 0.995);\n");
        sb.append("    // 1. Group nodes by community / package and compute centroids\n");
        sb.append("    const comms = new Map();\n");
        sb.append("    for (let i = 0; i < nodes.length; i++) {\n");
        sb.append("      const n = nodes[i];\n");
        sb.append("      if (hidePojos && n.isPojo) continue;\n");
        sb.append("      let c = comms.get(n.pkg);\n");
        sb.append("      if (!c) {\n");
        sb.append("        c = { pkg: n.pkg, x: 0, y: 0, count: 0, nodes: [] };\n");
        sb.append("        comms.set(n.pkg, c);\n");
        sb.append("      }\n");
        sb.append("      c.x += n.x; c.y += n.y; c.count++; c.nodes.push(n);\n");
        sb.append("    }\n");
        sb.append("    for (const c of comms.values()) {\n");
        sb.append("      if (c.count > 0) { c.x /= c.count; c.y /= c.count; }\n");
        sb.append("    }\n");
        sb.append("    // 2. Inter-community repulsion: push clusters apart into distinct bouquets\n");
        sb.append("    const commList = Array.from(comms.values());\n");
        sb.append("    for (let i = 0; i < commList.length; i++) {\n");
        sb.append("      for (let j = i + 1; j < commList.length; j++) {\n");
        sb.append("        const ca = commList[i], cb = commList[j];\n");
        sb.append("        const dx = cb.x - ca.x, dy = cb.y - ca.y;\n");
        sb.append("        const distSq = dx * dx + dy * dy || 1;\n");
        sb.append("        const dist = Math.sqrt(distSq);\n");
        sb.append("        const targetSep = (currentScope === 'arch' ? 220 : 350) + Math.sqrt(ca.count + cb.count) * 15;\n");
        sb.append("        if (dist < targetSep * 2.0) {\n");
        sb.append("          const rep = ((currentScope === 'arch' ? 1200 : 3000) / (distSq + 400)) * alpha;\n");
        sb.append("          const fx = (dx / dist) * rep, fy = (dy / dist) * rep;\n");
        sb.append("          for (let k = 0; k < ca.nodes.length; k++) {\n");
        sb.append("            ca.nodes[k].vx -= fx * 0.5; ca.nodes[k].vy -= fy * 0.5;\n");
        sb.append("          }\n");
        sb.append("          for (let k = 0; k < cb.nodes.length; k++) {\n");
        sb.append("            cb.nodes[k].vx += fx * 0.5; cb.nodes[k].vy += fy * 0.5;\n");
        sb.append("          }\n");
        sb.append("        }\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("    // 3. Intra-community cohesion: attract nodes toward community centroid\n");
        sb.append("    for (const c of commList) {\n");
        sb.append("      if (c.count <= 1) continue;\n");
        sb.append("      const idealRadius = currentScope === 'arch' ? 30 : Math.min(160, 20 + Math.sqrt(c.count) * 10);\n");
        sb.append("      for (let i = 0; i < c.nodes.length; i++) {\n");
        sb.append("        const nd = c.nodes[i];\n");
        sb.append("        const dx = c.x - nd.x, dy = c.y - nd.y;\n");
        sb.append("        const dist = Math.sqrt(dx * dx + dy * dy) || 0.1;\n");
        sb.append("        const pull = (dist - idealRadius) * (currentScope === 'arch' ? 0.008 : 0.005) * alpha;\n");
        sb.append("        nd.vx += (dx / dist) * pull;\n");
        sb.append("        nd.vy += (dy / dist) * pull;\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("    // 4. Intra-community separation: avoid overlap inside each cluster\n");
        sb.append("    for (const c of commList) {\n");
        sb.append("      const cnodes = c.nodes, clen = cnodes.length;\n");
        sb.append("      if (clen <= 1) continue;\n");
        sb.append("      const step = clen > 120 ? Math.ceil(clen / 100) : 1;\n");
        sb.append("      for (let i = 0; i < clen; i += step) {\n");
        sb.append("        for (let j = i + 1; j < clen; j += step) {\n");
        sb.append("          const n1 = cnodes[i], n2 = cnodes[j];\n");
        sb.append("          const dx = n2.x - n1.x, dy = n2.y - n1.y;\n");
        sb.append("          const distSq = dx * dx + dy * dy || 0.1;\n");
        sb.append("          const minDist = n1.radius + n2.radius + (currentScope === 'arch' ? 14 : 6);\n");
        sb.append("          if (distSq < minDist * minDist * 4) {\n");
        sb.append("            const dist = Math.sqrt(distSq);\n");
        sb.append("            const rep = (180 / (distSq + 20)) * alpha;\n");
        sb.append("            const fx = (dx / dist) * rep, fy = (dy / dist) * rep;\n");
        sb.append("            n1.vx -= fx; n1.vy -= fy;\n");
        sb.append("            n2.vx += fx; n2.vy += fy;\n");
        sb.append("          }\n");
        sb.append("        }\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("    // 5. Edge springs\n");
        sb.append("    const edgeStep = edges.length > 5000 ? 2 : 1;\n");
        sb.append("    for (let i = 0; i < edges.length; i += edgeStep) {\n");
        sb.append("      const e = edges[i];\n");
        sb.append("      if (hidePojos && (e.source.isPojo || e.target.isPojo)) continue;\n");
        sb.append("      const dx = e.target.x - e.source.x, dy = e.target.y - e.source.y;\n");
        sb.append("      const dist = Math.sqrt(dx * dx + dy * dy) || 1;\n");
        sb.append("      const isSamePkg = e.source.pkg === e.target.pkg;\n");
        sb.append("      const restLen = isSamePkg ? (currentScope === 'arch' ? 60 : 35) : (currentScope === 'arch' ? 160 : 220);\n");
        sb.append("      const k = (isSamePkg ? 0.006 : 0.001) * alpha;\n");
        sb.append("      const f = (dist - restLen) * k;\n");
        sb.append("      const fx = (dx / dist) * f, fy = (dy / dist) * f;\n");
        sb.append("      e.source.vx += fx; e.source.vy += fy;\n");
        sb.append("      e.target.vx -= fx; e.target.vy -= fy;\n");
        sb.append("    }\n");
        sb.append("    // 6. Integration, centering pull, damping, and velocity clamping\n");
        sb.append("    const maxSpeed = 3.0;\n");
        sb.append("    for (let i = 0; i < nodes.length; i++) {\n");
        sb.append("      const nd = nodes[i];\n");
        sb.append("      if (nd === draggedNode) continue;\n");
        sb.append("      nd.vx -= nd.x * 0.0003 * alpha;\n");
        sb.append("      nd.vy -= nd.y * 0.0003 * alpha;\n");
        sb.append("      nd.vx *= 0.88;\n");
        sb.append("      nd.vy *= 0.88;\n");
        sb.append("      const speed = Math.hypot(nd.vx, nd.vy);\n");
        sb.append("      if (speed > maxSpeed) {\n");
        sb.append("        nd.vx = (nd.vx / speed) * maxSpeed;\n");
        sb.append("        nd.vy = (nd.vy / speed) * maxSpeed;\n");
        sb.append("      }\n");
        sb.append("      nd.x += nd.vx; nd.y += nd.vy;\n");
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("  function draw() {\n");
        sb.append("    const dpr = window.devicePixelRatio || 1;\n");
        sb.append("    const W = canvas.width / dpr, H = canvas.height / dpr;\n");
        sb.append("    ctx.save();\n");
        sb.append("    ctx.scale(dpr, dpr);\n");
        sb.append("    ctx.fillStyle = '#07090e';\n");
        sb.append("    ctx.fillRect(0, 0, W, H);\n");
        sb.append("    ctx.save();\n");
        sb.append("    ctx.translate(panX, panY);\n");
        sb.append("    ctx.scale(zoom, zoom);\n");
        sb.append("    const hasSearch = searchQuery.length > 0;\n");
        sb.append("    // Draw Edges\n");
        sb.append("    edges.forEach(e => {\n");
        sb.append("      if (hidePojos && (e.source.isPojo || e.target.isPojo)) return;\n");
        sb.append("      const isHighlighted = activeNode && (e.source === activeNode || e.target === activeNode);\n");
        sb.append("      const srcMatch = !hasSearch || e.source.label.toLowerCase().includes(searchQuery) || e.source.id.toLowerCase().includes(searchQuery) || e.source.pkg.toLowerCase().includes(searchQuery);\n");
        sb.append("      const tgtMatch = !hasSearch || e.target.label.toLowerCase().includes(searchQuery) || e.target.id.toLowerCase().includes(searchQuery) || e.target.pkg.toLowerCase().includes(searchQuery);\n");
        sb.append("      const edgeVisible = !hasSearch || srcMatch || tgtMatch;\n");
        sb.append("      if (!edgeVisible && !isHighlighted) return;\n");
        sb.append("      const isSamePkg = e.source.pkg === e.target.pkg;\n");
        sb.append("      if (isHighlighted) {\n");
        sb.append("        ctx.strokeStyle = 'rgba(56,189,248,0.9)';\n");
        sb.append("        ctx.lineWidth = 1.8;\n");
        sb.append("      } else if (isSamePkg) {\n");
        sb.append("        ctx.strokeStyle = hasSearch ? 'rgba(148,163,184,0.12)' : 'rgba(148,163,184,0.18)';\n");
        sb.append("        ctx.lineWidth = 1.0;\n");
        sb.append("      } else {\n");
        sb.append("        ctx.strokeStyle = hasSearch ? 'rgba(148,163,184,0.06)' : 'rgba(148,163,184,0.08)';\n");
        sb.append("        ctx.lineWidth = 0.8;\n");
        sb.append("      }\n");
        sb.append("      ctx.beginPath();\n");
        sb.append("      ctx.moveTo(e.source.x, e.source.y);\n");
        sb.append("      ctx.lineTo(e.target.x, e.target.y);\n");
        sb.append("      ctx.stroke();\n");
        sb.append("    });\n");
        sb.append("    // Draw Nodes\n");
        sb.append("    const showLabels = zoom > 0.5;\n");
        sb.append("    const showAllLabels = zoom > 1.0;\n");
        sb.append("    nodes.forEach(n => {\n");
        sb.append("      if (hidePojos && n.isPojo) return;\n");
        sb.append("      const isMatch = !hasSearch || n.label.toLowerCase().includes(searchQuery) || n.id.toLowerCase().includes(searchQuery) || n.pkg.toLowerCase().includes(searchQuery);\n");
        sb.append("      const isSel = (n === activeNode);\n");
        sb.append("      const isHov = (n === hoveredNode);\n");
        sb.append("      const faded = hasSearch && !isMatch && !isSel;\n");
        sb.append("      const r = n.radius + (isSel ? 4 : isHov ? 2 : 0);\n");
        sb.append("      ctx.save();\n");
        sb.append("      if (hasSearch && isMatch && !isSel) {\n");
        sb.append("        ctx.shadowColor = n.color;\n");
        sb.append("        ctx.shadowBlur = 12;\n");
        sb.append("      } else if (isSel) {\n");
        sb.append("        ctx.shadowColor = '#fff';\n");
        sb.append("        ctx.shadowBlur = 10;\n");
        sb.append("      } else if (isHov) {\n");
        sb.append("        ctx.shadowColor = n.color;\n");
        sb.append("        ctx.shadowBlur = 6;\n");
        sb.append("      }\n");
        sb.append("      ctx.beginPath();\n");
        sb.append("      ctx.arc(n.x, n.y, r, 0, Math.PI * 2);\n");
        sb.append("      ctx.fillStyle = faded ? 'rgba(51,65,85,0.25)' : n.color;\n");
        sb.append("      ctx.fill();\n");
        sb.append("      ctx.shadowBlur = 0;\n");
        sb.append("      // Inner archetype accent ring for special types\n");
        sb.append("      if (!faded && n.arch && n.arch.cls !== 'badge-pojo') {\n");
        sb.append("        ctx.strokeStyle = n.arch.color;\n");
        sb.append("        ctx.lineWidth = 1.8;\n");
        sb.append("        ctx.stroke();\n");
        sb.append("      }\n");
        sb.append("      if (isSel) {\n");
        sb.append("        ctx.strokeStyle = '#fff';\n");
        sb.append("        ctx.lineWidth = 2.5;\n");
        sb.append("        ctx.stroke();\n");
        sb.append("      } else if (isHov) {\n");
        sb.append("        ctx.strokeStyle = 'rgba(255,255,255,0.6)';\n");
        sb.append("        ctx.lineWidth = 1.5;\n");
        sb.append("        ctx.stroke();\n");
        sb.append("      }\n");
        sb.append("      // Label rendering with LOD\n");
        sb.append("      const drawLabel = !faded && (showAllLabels || isSel || isHov || (hasSearch && isMatch) || (showLabels && (currentScope === 'arch' || n.inDegree + n.outDegree > 4)));\n");
        sb.append("      if (drawLabel) {\n");
        sb.append("        ctx.font = `${isSel ? 'bold 12px' : '10px'} monospace`;\n");
        sb.append("        ctx.fillStyle = isSel ? '#fff' : (isHov ? '#f1f5f9' : (hasSearch && isMatch ? '#e2e8f0' : 'rgba(203,213,225,0.85)'));\n");
        sb.append("        ctx.fillText(n.label, n.x + r + 3, n.y + 4);\n");
        sb.append("      }\n");
        sb.append("      ctx.restore();\n");
        sb.append("    });\n");
        sb.append("    ctx.restore();\n");
        sb.append("    ctx.restore();\n");
        sb.append("  }\n");
        sb.append("  function loop() {\n");
        sb.append("    simulate();\n");
        sb.append("    draw();\n");
        sb.append("    requestAnimationFrame(loop);\n");
        sb.append("  }\n");
        sb.append("  function resizeCanvas() {\n");
        sb.append("    const dpr = window.devicePixelRatio || 1;\n");
        sb.append("    const wrap = canvas.parentElement;\n");
        sb.append("    const W = wrap.clientWidth || window.innerWidth;\n");
        sb.append("    const H = wrap.clientHeight || (window.innerHeight - 54);\n");
        sb.append("    canvas.width = W * dpr;\n");
        sb.append("    canvas.height = H * dpr;\n");
        sb.append("  }\n");
        sb.append("  function getNodeAt(x, y) {\n");
        sb.append("    const wx = (x - panX) / zoom, wy = (y - panY) / zoom;\n");
        sb.append("    for (let i = nodes.length - 1; i >= 0; i--) {\n");
        sb.append("      const n = nodes[i];\n");
        sb.append("      if (hidePojos && n.isPojo) continue;\n");
        sb.append("      const dx = n.x - wx, dy = n.y - wy;\n");
        sb.append("      if (dx * dx + dy * dy <= (n.radius + 6) * (n.radius + 6)) return n;\n");
        sb.append("    }\n");
        sb.append("    return null;\n");
        sb.append("  }\n");
        sb.append("  function resetInspector() {\n");
        sb.append("    const hint = document.getElementById('no-selection-hint');\n");
        sb.append("    const content = document.getElementById('inspector-content');\n");
        sb.append("    const badge = document.getElementById('inspector-type-badge');\n");
        sb.append("    if (hint) hint.style.display = '';\n");
        sb.append("    if (content) content.style.display = 'none';\n");
        sb.append("    if (badge) badge.style.display = 'none';\n");
        sb.append("    activeNode = null;\n");
        sb.append("  }\n");
        sb.append("  function selectNode(n) {\n");
        sb.append("    activeNode = n;\n");
        sb.append("    const hint = document.getElementById('no-selection-hint');\n");
        sb.append("    const content = document.getElementById('inspector-content');\n");
        sb.append("    const badge = document.getElementById('inspector-type-badge');\n");
        sb.append("    if (!n) { resetInspector(); return; }\n");
        sb.append("    if (hint) hint.style.display = 'none';\n");
        sb.append("    if (content) content.style.display = '';\n");
        sb.append("    if (badge) { badge.textContent = n.type; badge.style.display = ''; }\n");
        sb.append("    document.getElementById('inspector-name').textContent = n.label;\n");
        sb.append("    document.getElementById('inspector-pkg').textContent = n.pkg;\n");
        sb.append("    document.getElementById('inspector-indegree').textContent = n.inDegree;\n");
        sb.append("    document.getElementById('inspector-outdegree').textContent = n.outDegree;\n");
        sb.append("    const archWrap = document.getElementById('inspector-archetype');\n");
        sb.append("    archWrap.innerHTML = n.arch ? `<span class=\"entity-badge ${n.arch.cls || 'badge-class'}\">${n.arch.text}</span>` : '';\n");
        sb.append("    const connList = document.getElementById('inspector-connections');\n");
        sb.append("    connList.innerHTML = '';\n");
        sb.append("    const sorted = [...n.neighbors].sort((a, b) => a.kind.localeCompare(b.kind));\n");
        sb.append("    sorted.forEach(nb => {\n");
        sb.append("      const li = document.createElement('li');\n");
        sb.append("      li.className = 'connection-item';\n");
        sb.append("      const arrow = nb.kind === 'calls' ? '→' : '←';\n");
        sb.append("      const color = nb.kind === 'calls' ? '#34d399' : '#f472b6';\n");
        sb.append("      li.innerHTML = `<span class=\"conn-label\"><span style=\"color:${color};font-weight:700;\">${arrow}</span> ${nb.node.label}</span><span class=\"conn-pkg\" title=\"${nb.node.pkg}\">${nb.node.pkg.split('.').pop()}</span>`;\n");
        sb.append("      li.onclick = () => selectNode(nb.node);\n");
        sb.append("      connList.appendChild(li);\n");
        sb.append("    });\n");
        sb.append("    // Auto-open sidebar if collapsed\n");
        sb.append("    const sidebar = document.getElementById('inspector-sidebar');\n");
        sb.append("    if (sidebar && sidebar.classList.contains('collapsed')) {\n");
        sb.append("      sidebar.classList.remove('collapsed');\n");
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("  function updateSearchClear() {\n");
        sb.append("    const btn = document.getElementById('search-clear');\n");
        sb.append("    if (btn) btn.classList.toggle('visible', searchQuery.length > 0);\n");
        sb.append("  }\n");
        sb.append("  // --- Event Listeners ---\n");
        sb.append("  canvas.addEventListener('mousedown', e => {\n");
        sb.append("    const r = canvas.getBoundingClientRect();\n");
        sb.append("    const x = e.clientX - r.left, y = e.clientY - r.top;\n");
        sb.append("    const hit = getNodeAt(x, y);\n");
        sb.append("    if (hit) { draggedNode = hit; selectNode(hit); }\n");
        sb.append("    else { isDragging = true; dragStartX = e.clientX - panX; dragStartY = e.clientY - panY; }\n");
        sb.append("  });\n");
        sb.append("  window.addEventListener('mousemove', e => {\n");
        sb.append("    const r = canvas.getBoundingClientRect();\n");
        sb.append("    const x = e.clientX - r.left, y = e.clientY - r.top;\n");
        sb.append("    if (draggedNode) {\n");
        sb.append("      draggedNode.x = (x - panX) / zoom;\n");
        sb.append("      draggedNode.y = (y - panY) / zoom;\n");
        sb.append("    } else if (isDragging) {\n");
        sb.append("      panX = e.clientX - dragStartX;\n");
        sb.append("      panY = e.clientY - dragStartY;\n");
        sb.append("    } else {\n");
        sb.append("      const hit = getNodeAt(x, y);\n");
        sb.append("      hoveredNode = hit;\n");
        sb.append("      canvas.style.cursor = hit ? 'pointer' : 'grab';\n");
        sb.append("      if (hit) {\n");
        sb.append("        tooltip.style.display = 'block';\n");
        sb.append("        const tx = Math.min(e.clientX + 16, window.innerWidth - 200);\n");
        sb.append("        const ty = Math.min(e.clientY + 16, window.innerHeight - 80);\n");
        sb.append("        tooltip.style.left = tx + 'px';\n");
        sb.append("        tooltip.style.top = ty + 'px';\n");
        sb.append("        const idShort = hit.id.length > 60 ? '…' + hit.id.slice(-57) : hit.id;\n");
        sb.append("        tooltip.innerHTML = `<strong>${hit.label}</strong><br><span style=\"color:#94a3b8;font-size:10px;\">${idShort}</span>${hit.arch ? '<br><span style=\"color:#34d399;font-size:10px;\">' + hit.arch.text + '</span>' : ''}<br><span style=\"color:#64748b;font-size:10px;\">↑${hit.inDegree} ↓${hit.outDegree}</span>`;\n");
        sb.append("      } else {\n");
        sb.append("        tooltip.style.display = 'none';\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("  });\n");
        sb.append("  window.addEventListener('mouseup', () => { isDragging = false; draggedNode = null; canvas.style.cursor = 'grab'; });\n");
        sb.append("  canvas.addEventListener('wheel', e => {\n");
        sb.append("    e.preventDefault();\n");
        sb.append("    const r = canvas.getBoundingClientRect();\n");
        sb.append("    const mx = e.clientX - r.left, my = e.clientY - r.top;\n");
        sb.append("    const factor = e.deltaY < 0 ? 1.12 : 0.89;\n");
        sb.append("    const newZoom = Math.max(0.05, Math.min(5.0, zoom * factor));\n");
        sb.append("    panX = mx - (mx - panX) * (newZoom / zoom);\n");
        sb.append("    panY = my - (my - panY) * (newZoom / zoom);\n");
        sb.append("    zoom = newZoom;\n");
        sb.append("  }, { passive: false });\n");
        sb.append("  // Touch support\n");
        sb.append("  let touchStartDist = 0, touchStartZoom = 1;\n");
        sb.append("  canvas.addEventListener('touchstart', e => {\n");
        sb.append("    if (e.touches.length === 2) {\n");
        sb.append("      touchStartDist = Math.hypot(e.touches[0].clientX - e.touches[1].clientX, e.touches[0].clientY - e.touches[1].clientY);\n");
        sb.append("      touchStartZoom = zoom;\n");
        sb.append("    } else if (e.touches.length === 1) {\n");
        sb.append("      isDragging = true;\n");
        sb.append("      dragStartX = e.touches[0].clientX - panX;\n");
        sb.append("      dragStartY = e.touches[0].clientY - panY;\n");
        sb.append("    }\n");
        sb.append("    e.preventDefault();\n");
        sb.append("  }, { passive: false });\n");
        sb.append("  canvas.addEventListener('touchmove', e => {\n");
        sb.append("    if (e.touches.length === 2) {\n");
        sb.append("      const dist = Math.hypot(e.touches[0].clientX - e.touches[1].clientX, e.touches[0].clientY - e.touches[1].clientY);\n");
        sb.append("      zoom = Math.max(0.05, Math.min(5.0, touchStartZoom * dist / touchStartDist));\n");
        sb.append("    } else if (e.touches.length === 1 && isDragging) {\n");
        sb.append("      panX = e.touches[0].clientX - dragStartX;\n");
        sb.append("      panY = e.touches[0].clientY - dragStartY;\n");
        sb.append("    }\n");
        sb.append("    e.preventDefault();\n");
        sb.append("  }, { passive: false });\n");
        sb.append("  canvas.addEventListener('touchend', () => { isDragging = false; });\n");
        sb.append("  const searchInput = document.getElementById('search-input');\n");
        sb.append("  searchInput.addEventListener('input', e => {\n");
        sb.append("    searchQuery = e.target.value.trim().toLowerCase();\n");
        sb.append("    updateSearchClear();\n");
        sb.append("  });\n");
        sb.append("  const searchClear = document.getElementById('search-clear');\n");
        sb.append("  if (searchClear) {\n");
        sb.append("    searchClear.addEventListener('click', () => {\n");
        sb.append("      searchInput.value = ''; searchQuery = ''; updateSearchClear();\n");
        sb.append("    });\n");
        sb.append("  }\n");
        sb.append("  document.getElementById('btn-scope-arch').addEventListener('click', () => {\n");
        sb.append("    currentScope = 'arch';\n");
        sb.append("    document.getElementById('btn-scope-arch').classList.add('active');\n");
        sb.append("    document.getElementById('btn-scope-methods').classList.remove('active');\n");
        sb.append("    loadGraphData();\n");
        sb.append("  });\n");
        sb.append("  document.getElementById('btn-scope-methods').addEventListener('click', () => {\n");
        sb.append("    currentScope = 'methods';\n");
        sb.append("    document.getElementById('btn-scope-methods').classList.add('active');\n");
        sb.append("    document.getElementById('btn-scope-arch').classList.remove('active');\n");
        sb.append("    loadGraphData();\n");
        sb.append("  });\n");
        sb.append("  document.getElementById('btn-fit').addEventListener('click', fitView);\n");
        sb.append("  document.getElementById('btn-pause').addEventListener('click', () => {\n");
        sb.append("    physicsRunning = !physicsRunning;\n");
        sb.append("    const btn = document.getElementById('btn-pause');\n");
        sb.append("    btn.textContent = physicsRunning ? '⏸ Pause' : '▶ Run Physics';\n");
        sb.append("    btn.classList.toggle('active', physicsRunning);\n");
        sb.append("    if (physicsRunning) {\n");
        sb.append("      simAlpha = 1.0;\n");
        sb.append("    } else {\n");
        sb.append("      nodes.forEach(n => { n.vx = 0; n.vy = 0; });\n");
        sb.append("    }\n");
        sb.append("  });\n");
        sb.append("  document.getElementById('btn-pojo').addEventListener('click', () => {\n");
        sb.append("    hidePojos = !hidePojos;\n");
        sb.append("    const btn = document.getElementById('btn-pojo');\n");
        sb.append("    btn.classList.toggle('active', hidePojos);\n");
        sb.append("    btn.textContent = hidePojos ? '⚡ Show POJOs' : '⚡ Hide POJOs';\n");
        sb.append("    updateStats();\n");
        sb.append("  });\n");
        sb.append("  // Sidebar toggle\n");
        sb.append("  const sidebarToggle = document.getElementById('btn-sidebar-toggle');\n");
        sb.append("  if (sidebarToggle) {\n");
        sb.append("    sidebarToggle.addEventListener('click', () => {\n");
        sb.append("      const sidebar = document.getElementById('inspector-sidebar');\n");
        sb.append("      if (sidebar) sidebar.classList.toggle('collapsed');\n");
        sb.append("    });\n");
        sb.append("  }\n");
        sb.append("  // Legend collapse\n");
        sb.append("  const legendHeader = document.getElementById('legend-header');\n");
        sb.append("  if (legendHeader) {\n");
        sb.append("    legendHeader.addEventListener('click', () => {\n");
        sb.append("      const panel = document.getElementById('legend-panel');\n");
        sb.append("      if (panel) panel.classList.toggle('collapsed');\n");
        sb.append("    });\n");
        sb.append("  }\n");
        sb.append("  window.addEventListener('resize', () => { resizeCanvas(); fitView(); });\n");
        sb.append("  // Init sequence: resize canvas, load data, hide loading overlay\n");
        sb.append("  resizeCanvas();\n");
        sb.append("  requestAnimationFrame(() => {\n");
        sb.append("    loadGraphData();\n");
        sb.append("    loop();\n");
        sb.append("    // Hide loading overlay after first frame\n");
        sb.append("    setTimeout(() => {\n");
        sb.append("      if (loadingOverlay) loadingOverlay.classList.add('hidden');\n");
        sb.append("      setTimeout(() => { if (loadingOverlay) loadingOverlay.style.display = 'none'; }, 500);\n");
        sb.append("    }, 200);\n");
        sb.append("  });\n");
        sb.append("})();\n");
        sb.append("</script>\n");
        sb.append("</body>\n</html>");
        return sb.toString();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private static String escapeCsv(String value) {
        if (value == null) return "\"\"";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
