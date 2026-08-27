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
        sb.append("<title>CodeLens Graph Snapshot - ").append(escapeHtml(projectName)).append("</title>\n");
        sb.append("<style>\n");
        sb.append(":root {\n");
        sb.append("  --bg-primary: #07090e; --bg-surface: #0f172a; --bg-card: #1e293b; --border: #334155;\n");
        sb.append("  --text-main: #f8fafc; --text-muted: #94a3b8; --accent: #10b981; --accent-hover: #059669;\n");
        sb.append("  --cyan: #06b6d4; --blue: #3b82f6; --purple: #a855f7; --red: #ef4444; --orange: #f97316;\n");
        sb.append("}\n");
        sb.append("* { box-sizing: border-box; margin: 0; padding: 0; }\n");
        sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background: var(--bg-primary); color: var(--text-main); height: 100vh; display: flex; flex-direction: column; overflow: hidden; }\n");
        sb.append("header { height: 50px; background: var(--bg-surface); border-bottom: 1px solid var(--border); display: flex; align-items: center; justify-content: space-between; padding: 0 16px; z-index: 100; }\n");
        sb.append(".header-left { display: flex; align-items: center; gap: 12px; }\n");
        sb.append(".logo { font-size: 16px; font-weight: 800; color: var(--accent); display: flex; align-items: center; gap: 6px; letter-spacing: 0.5px; }\n");
        sb.append(".project-badge { background: rgba(16,185,129,0.12); color: #34d399; border: 1px solid rgba(16,185,129,0.3); padding: 3px 10px; border-radius: 6px; font-size: 12px; font-weight: 600; font-family: monospace; }\n");
        sb.append(".header-center { display: flex; align-items: center; gap: 16px; }\n");
        sb.append(".search-box { position: relative; width: 260px; }\n");
        sb.append(".search-box input { width: 100%; background: #07090e; border: 1px solid var(--border); border-radius: 6px; padding: 6px 10px 6px 28px; color: var(--text-main); font-size: 12px; outline: none; transition: border-color 0.2s; }\n");
        sb.append(".search-box input:focus { border-color: var(--accent); }\n");
        sb.append(".search-box .icon { position: absolute; left: 8px; top: 7px; font-size: 12px; color: var(--text-muted); }\n");
        sb.append(".scope-toggle { display: flex; background: #07090e; border: 1px solid var(--border); border-radius: 6px; padding: 2px; }\n");
        sb.append(".scope-btn { background: transparent; border: none; color: var(--text-muted); padding: 4px 10px; font-size: 12px; font-weight: 600; border-radius: 4px; cursor: pointer; transition: all 0.2s; }\n");
        sb.append(".scope-btn.active { background: var(--accent); color: #000; font-weight: 700; }\n");
        sb.append(".header-right { display: flex; align-items: center; gap: 10px; font-size: 12px; color: var(--text-muted); }\n");
        sb.append(".main-content { flex: 1; position: relative; display: flex; overflow: hidden; }\n");
        sb.append("#graph-canvas { flex: 1; width: 100%; height: 100%; cursor: grab; }\n");
        sb.append("#graph-canvas:active { cursor: grabbing; }\n");
        sb.append(".sidebar { width: 340px; background: var(--bg-surface); border-left: 1px solid var(--border); display: flex; flex-direction: column; z-index: 50; transition: transform 0.2s; }\n");
        sb.append(".sidebar.collapsed { transform: translateX(100%); margin-left: -340px; }\n");
        sb.append(".sidebar-header { padding: 14px 16px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; font-size: 13px; font-weight: 700; }\n");
        sb.append(".sidebar-body { flex: 1; overflow-y: auto; padding: 16px; }\n");
        sb.append(".entity-card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 14px; margin-bottom: 14px; }\n");
        sb.append(".entity-title { font-size: 14px; font-weight: 700; word-break: break-all; margin-bottom: 6px; font-family: monospace; color: #67e8f9; }\n");
        sb.append(".entity-badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 10px; font-weight: 700; text-transform: uppercase; margin-bottom: 8px; }\n");
        sb.append(".badge-class { background: rgba(59,130,246,0.2); color: #60a5fa; border: 1px solid rgba(59,130,246,0.4); }\n");
        sb.append(".badge-method { background: rgba(16,185,129,0.2); color: #34d399; border: 1px solid rgba(16,185,129,0.4); }\n");
        sb.append(".badge-fetch { background: rgba(6,182,212,0.2); color: #22d3ee; border: 1px solid rgba(6,182,212,0.4); }\n");
        sb.append(".badge-mutate { background: rgba(249,115,22,0.2); color: #fb923c; border: 1px solid rgba(249,115,22,0.4); }\n");
        sb.append(".badge-batch { background: rgba(168,85,247,0.2); color: #c084fc; border: 1px solid rgba(168,85,247,0.4); }\n");
        sb.append(".badge-grabber { background: rgba(236,72,153,0.2); color: #f472b6; border: 1px solid rgba(236,72,153,0.4); }\n");
        sb.append(".prop-row { display: flex; justify-content: space-between; font-size: 12px; margin: 6px 0; }\n");
        sb.append(".prop-name { color: var(--text-muted); }\n");
        sb.append(".prop-val { font-weight: 600; font-family: monospace; }\n");
        sb.append(".connections-list { list-style: none; margin-top: 10px; max-height: 200px; overflow-y: auto; }\n");
        sb.append(".connection-item { font-size: 11px; font-family: monospace; padding: 4px 8px; border-radius: 4px; margin-bottom: 4px; background: rgba(255,255,255,0.03); display: flex; align-items: center; justify-content: space-between; cursor: pointer; }\n");
        sb.append(".connection-item:hover { background: rgba(255,255,255,0.08); }\n");
        sb.append(".hud-controls { position: absolute; bottom: 20px; left: 20px; display: flex; gap: 8px; z-index: 40; background: rgba(15,23,42,0.85); backdrop-filter: blur(8px); border: 1px solid var(--border); border-radius: 8px; padding: 6px 10px; }\n");
        sb.append(".hud-btn { background: #1e293b; border: 1px solid var(--border); color: var(--text-main); border-radius: 6px; padding: 6px 12px; font-size: 12px; font-weight: 600; cursor: pointer; display: flex; align-items: center; gap: 6px; transition: all 0.15s; }\n");
        sb.append(".hud-btn:hover { background: #334155; border-color: var(--accent); }\n");
        sb.append(".hud-btn.active { background: var(--accent); color: #000; font-weight: 700; }\n");
        sb.append(".legend-panel { position: absolute; top: 16px; left: 16px; max-width: 260px; max-height: 380px; overflow-y: auto; background: rgba(15,23,42,0.88); backdrop-filter: blur(8px); border: 1px solid var(--border); border-radius: 8px; padding: 12px; font-size: 11px; z-index: 40; }\n");
        sb.append(".legend-title { font-weight: 700; margin-bottom: 8px; color: var(--text-muted); text-transform: uppercase; font-size: 10px; letter-spacing: 0.5px; }\n");
        sb.append(".legend-item { display: flex; align-items: center; gap: 6px; margin: 4px 0; }\n");
        sb.append(".legend-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }\n");
        sb.append(".floating-tooltip { position: absolute; pointer-events: none; background: rgba(15,23,42,0.95); border: 1px solid var(--border); border-radius: 6px; padding: 8px 12px; font-size: 12px; z-index: 1000; display: none; box-shadow: 0 4px 20px rgba(0,0,0,0.5); }\n");
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<header>\n");
        sb.append("  <div class=\"header-left\">\n");
        sb.append("    <span class=\"logo\">⬡ CODELENS</span>\n");
        sb.append("    <span class=\"project-badge\">").append(escapeHtml(projectName)).append("</span>\n");
        sb.append("  </div>\n");
        sb.append("  <div class=\"header-center\">\n");
        sb.append("    <div class=\"search-box\">\n");
        sb.append("      <span class=\"icon\">🔍</span>\n");
        sb.append("      <input id=\"search-input\" type=\"text\" placeholder=\"Filter classes / methods…\" />\n");
        sb.append("    </div>\n");
        sb.append("    <div class=\"scope-toggle\">\n");
        sb.append("      <button id=\"btn-scope-arch\" class=\"scope-btn active\">Classes</button>\n");
        sb.append("      <button id=\"btn-scope-methods\" class=\"scope-btn\">Methods</button>\n");
        sb.append("    </div>\n");
        sb.append("  </div>\n");
        sb.append("  <div class=\"header-right\">\n");
        sb.append("    <span>Interactive Graph Snapshot</span> &bull; <span>Generated ").append(escapeHtml(archData != null ? archData.generatedAt : "Today")).append("</span>\n");
        sb.append("  </div>\n");
        sb.append("</header>\n");

        sb.append("<div class=\"main-content\">\n");
        sb.append("  <canvas id=\"graph-canvas\"></canvas>\n");

        sb.append("  <div class=\"legend-panel\">\n");
        sb.append("    <div class=\"legend-title\">Packages & Archetypes</div>\n");
        sb.append("    <div id=\"legend-items\"></div>\n");
        sb.append("  </div>\n");

        sb.append("  <div class=\"hud-controls\">\n");
        sb.append("    <button class=\"hud-btn\" id=\"btn-fit\" title=\"Fit graph in view\">⛶ Fit View</button>\n");
        sb.append("    <button class=\"hud-btn\" id=\"btn-pause\" title=\"Pause/resume physics\">⏸ Pause Physics</button>\n");
        sb.append("    <button class=\"hud-btn\" id=\"btn-pojo\" title=\"Toggle POJOs\">⚡ Hide POJOs</button>\n");
        sb.append("  </div>\n");

        sb.append("  <div class=\"sidebar\" id=\"inspector-sidebar\">\n");
        sb.append("    <div class=\"sidebar-header\">\n");
        sb.append("      <span>Entity Inspector</span>\n");
        sb.append("      <span id=\"inspector-type-badge\" class=\"entity-badge badge-class\">CLASS</span>\n");
        sb.append("    </div>\n");
        sb.append("    <div class=\"sidebar-body\">\n");
        sb.append("      <div class=\"entity-card\">\n");
        sb.append("        <div class=\"entity-title\" id=\"inspector-name\">Select a node</div>\n");
        sb.append("        <div id=\"inspector-archetype\"></div>\n");
        sb.append("        <div class=\"prop-row\"><span class=\"prop-name\">Package:</span><span class=\"prop-val\" id=\"inspector-pkg\">-</span></div>\n");
        sb.append("        <div class=\"prop-row\"><span class=\"prop-name\">In-Degree (Incoming):</span><span class=\"prop-val\" id=\"inspector-indegree\">0</span></div>\n");
        sb.append("        <div class=\"prop-row\"><span class=\"prop-name\">Out-Degree (Outgoing):</span><span class=\"prop-val\" id=\"inspector-outdegree\">0</span></div>\n");
        sb.append("      </div>\n");
        sb.append("      <div style=\"font-size:12px; font-weight:700; margin-bottom:8px; color:var(--text-muted);\">Connected Entities</div>\n");
        sb.append("      <ul class=\"connections-list\" id=\"inspector-connections\"></ul>\n");
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
        sb.append("  const PKG_PALETTE = ['#38bdf8', '#34d399', '#f472b6', '#a78bfa', '#fb923c', '#facc15', '#4ade80', '#2dd4bf', '#818cf8', '#e879f9'];\n");
        sb.append("  function getPkgColor(pkg) {\n");
        sb.append("    if (!pkg) return '#94a3b8';\n");
        sb.append("    let hash = 0;\n");
        sb.append("    for (let i = 0; i < pkg.length; i++) hash = (hash << 5) - hash + pkg.charCodeAt(i);\n");
        sb.append("    return PKG_PALETTE[Math.abs(hash) % PKG_PALETTE.length];\n");
        sb.append("  }\n");
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
        sb.append("  function loadGraphData() {\n");
        sb.append("    const raw = currentScope === 'arch' ? archGraph : fullGraph;\n");
        sb.append("    const rawNodes = raw.nodes || [];\n");
        sb.append("    const rawEdges = raw.edges || [];\n");
        sb.append("    nodeMap.clear();\n");
        sb.append("    nodes = [];\n");
        sb.append("    const pkgs = new Set();\n");
        sb.append("    rawNodes.forEach((rn, i) => {\n");
        sb.append("      const pkg = rn.packageFqn || (rn.id.includes('.') ? rn.id.substring(0, rn.id.lastIndexOf('.')) : '(default)');\n");
        sb.append("      pkgs.add(pkg);\n");
        sb.append("      const arch = classify(rn.label || rn.id, rn.id, pkg);\n");
        sb.append("      const node = {\n");
        sb.append("        id: rn.id,\n");
        sb.append("        label: rn.label || rn.id.split('.').pop(),\n");
        sb.append("        type: rn.type || 'CLASS',\n");
        sb.append("        pkg,\n");
        sb.append("        color: getPkgColor(pkg),\n");
        sb.append("        arch,\n");
        sb.append("        isPojo: arch && arch.isPojo,\n");
        sb.append("        x: (Math.random() - 0.5) * 800,\n");
        sb.append("        y: (Math.random() - 0.5) * 600,\n");
        sb.append("        vx: 0, vy: 0,\n");
        sb.append("        radius: currentScope === 'arch' ? 9 : 6,\n");
        sb.append("        inDegree: 0, outDegree: 0,\n");
        sb.append("        neighbors: []\n");
        sb.append("      };\n");
        sb.append("      nodes.push(node);\n");
        sb.append("      nodeMap.set(node.id, node);\n");
        sb.append("    });\n");
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
        sb.append("    fitView();\n");
        sb.append("  }\n");
        sb.append("  function renderLegend(pkgs) {\n");
        sb.append("    const wrap = document.getElementById('legend-items');\n");
        sb.append("    wrap.innerHTML = '';\n");
        sb.append("    Array.from(pkgs).slice(0, 15).forEach(pkg => {\n");
        sb.append("      const row = document.createElement('div');\n");
        sb.append("      row.className = 'legend-item';\n");
        sb.append("      row.innerHTML = `<span class=\"legend-dot\" style=\"background:${getPkgColor(pkg)}\"></span><span style=\"white-space:nowrap; overflow:hidden; text-overflow:ellipsis;\">${pkg}</span>`;\n");
        sb.append("      wrap.appendChild(row);\n");
        sb.append("    });\n");
        sb.append("  }\n");
        sb.append("  function fitView() {\n");
        sb.append("    if (nodes.length === 0) return;\n");
        sb.append("    const dpr = window.devicePixelRatio || 1;\n");
        sb.append("    panX = canvas.width / (2 * dpr);\n");
        sb.append("    panY = canvas.height / (2 * dpr);\n");
        sb.append("    zoom = currentScope === 'arch' ? 0.9 : 0.65;\n");
        sb.append("  }\n");
        sb.append("  function simulate() {\n");
        sb.append("    if (!physicsRunning) return;\n");
        sb.append("    const alpha = 0.08;\n");
        sb.append("    for (let i = 0; i < nodes.length; i++) {\n");
        sb.append("      const n1 = nodes[i];\n");
        sb.append("      if (hidePojos && n1.isPojo) continue;\n");
        sb.append("      for (let j = i + 1; j < nodes.length; j++) {\n");
        sb.append("        const n2 = nodes[j];\n");
        sb.append("        if (hidePojos && n2.isPojo) continue;\n");
        sb.append("        const dx = n2.x - n1.x;\n");
        sb.append("        const dy = n2.y - n1.y;\n");
        sb.append("        const distSq = dx * dx + dy * dy || 1;\n");
        sb.append("        if (distSq < 40000) {\n");
        sb.append("          const force = (300 / distSq) * alpha;\n");
        sb.append("          n1.vx -= dx * force; n1.vy -= dy * force;\n");
        sb.append("          n2.vx += dx * force; n2.vy += dy * force;\n");
        sb.append("        }\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("    edges.forEach(e => {\n");
        sb.append("      if (hidePojos && (e.source.isPojo || e.target.isPojo)) return;\n");
        sb.append("      const dx = e.target.x - e.source.x;\n");
        sb.append("      const dy = e.target.y - e.source.y;\n");
        sb.append("      const dist = Math.sqrt(dx * dx + dy * dy) || 1;\n");
        sb.append("      const force = (dist - 80) * 0.003 * alpha;\n");
        sb.append("      e.source.vx += dx * force; e.source.vy += dy * force;\n");
        sb.append("      e.target.vx -= dx * force; e.target.vy -= dy * force;\n");
        sb.append("    });\n");
        sb.append("    nodes.forEach(n => {\n");
        sb.append("      if (n === draggedNode) return;\n");
        sb.append("      n.vx *= 0.88; n.vy *= 0.88;\n");
        sb.append("      n.x += n.vx; n.y += n.vy;\n");
        sb.append("      n.vx -= n.x * 0.0004;\n");
        sb.append("      n.vy -= n.y * 0.0004;\n");
        sb.append("    });\n");
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
        sb.append("    // Draw Edges\n");
        sb.append("    ctx.lineWidth = 1.2;\n");
        sb.append("    edges.forEach(e => {\n");
        sb.append("      if (hidePojos && (e.source.isPojo || e.target.isPojo)) return;\n");
        sb.append("      const isHighlighted = activeNode && (e.source === activeNode || e.target === activeNode);\n");
        sb.append("      ctx.strokeStyle = isHighlighted ? '#38bdf8' : 'rgba(148, 163, 184, 0.18)';\n");
        sb.append("      ctx.beginPath();\n");
        sb.append("      ctx.moveTo(e.source.x, e.source.y);\n");
        sb.append("      ctx.lineTo(e.target.x, e.target.y);\n");
        sb.append("      ctx.stroke();\n");
        sb.append("    });\n");
        sb.append("    // Draw Nodes\n");
        sb.append("    nodes.forEach(n => {\n");
        sb.append("      if (hidePojos && n.isPojo) return;\n");
        sb.append("      const isMatch = !searchQuery || n.label.toLowerCase().includes(searchQuery) || n.id.toLowerCase().includes(searchQuery);\n");
        sb.append("      const isSel = (n === activeNode);\n");
        sb.append("      const isHov = (n === hoveredNode);\n");
        sb.append("      ctx.save();\n");
        sb.append("      ctx.beginPath();\n");
        sb.append("      ctx.arc(n.x, n.y, n.radius + (isSel ? 4 : isHov ? 2 : 0), 0, Math.PI * 2);\n");
        sb.append("      ctx.fillStyle = isMatch ? (n.arch ? n.arch.color : n.color) : 'rgba(51, 65, 85, 0.4)';\n");
        sb.append("      ctx.fill();\n");
        sb.append("      if (isSel || isHov) {\n");
        sb.append("        ctx.strokeStyle = '#fff';\n");
        sb.append("        ctx.lineWidth = isSel ? 3 : 2;\n");
        sb.append("        ctx.stroke();\n");
        sb.append("      }\n");
        sb.append("      // Label\n");
        sb.append("      if (zoom > 0.6 || isSel || isHov || isMatch) {\n");
        sb.append("        ctx.font = `${isSel ? 'bold 12px' : '11px'} monospace`;\n");
        sb.append("        ctx.fillStyle = isMatch ? '#f8fafc' : 'rgba(148, 163, 184, 0.4)';\n");
        sb.append("        ctx.fillText(n.label, n.x + n.radius + 4, n.y + 4);\n");
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
        sb.append("  function resize() {\n");
        sb.append("    const dpr = window.devicePixelRatio || 1;\n");
        sb.append("    canvas.width = canvas.parentElement.clientWidth * dpr;\n");
        sb.append("    canvas.height = canvas.parentElement.clientHeight * dpr;\n");
        sb.append("    draw();\n");
        sb.append("  }\n");
        sb.append("  function getNodeAt(x, y) {\n");
        sb.append("    const wx = (x - panX) / zoom;\n");
        sb.append("    const wy = (y - panY) / zoom;\n");
        sb.append("    for (let i = nodes.length - 1; i >= 0; i--) {\n");
        sb.append("      const n = nodes[i];\n");
        sb.append("      if (hidePojos && n.isPojo) continue;\n");
        sb.append("      const dx = n.x - wx, dy = n.y - wy;\n");
        sb.append("      if (dx * dx + dy * dy <= (n.radius + 6) * (n.radius + 6)) return n;\n");
        sb.append("    }\n");
        sb.append("    return null;\n");
        sb.append("  }\n");
        sb.append("  function selectNode(n) {\n");
        sb.append("    activeNode = n;\n");
        sb.append("    if (!n) return;\n");
        sb.append("    document.getElementById('inspector-name').textContent = n.label;\n");
        sb.append("    document.getElementById('inspector-pkg').textContent = n.pkg;\n");
        sb.append("    document.getElementById('inspector-indegree').textContent = n.inDegree;\n");
        sb.append("    document.getElementById('inspector-outdegree').textContent = n.outDegree;\n");
        sb.append("    document.getElementById('inspector-type-badge').textContent = n.type;\n");
        sb.append("    const archWrap = document.getElementById('inspector-archetype');\n");
        sb.append("    if (n.arch) {\n");
        sb.append("      archWrap.innerHTML = `<span class=\"entity-badge ${n.arch.cls || 'badge-class'}\">${n.arch.text}</span>`;\n");
        sb.append("    } else {\n");
        sb.append("      archWrap.innerHTML = '';\n");
        sb.append("    }\n");
        sb.append("    const connList = document.getElementById('inspector-connections');\n");
        sb.append("    connList.innerHTML = '';\n");
        sb.append("    n.neighbors.forEach(nb => {\n");
        sb.append("      const li = document.createElement('li');\n");
        sb.append("      li.className = 'connection-item';\n");
        sb.append("      li.innerHTML = `<span>${nb.kind === 'calls' ? '➡️ Calls' : '⬅️ Called by'} <strong>${nb.node.label}</strong></span><span style=\"color:var(--text-muted); font-size:10px;\">${nb.node.pkg}</span>`;\n");
        sb.append("      li.onclick = () => selectNode(nb.node);\n");
        sb.append("      connList.appendChild(li);\n");
        sb.append("    });\n");
        sb.append("  }\n");
        sb.append("  // Event Listeners\n");
        sb.append("  canvas.addEventListener('mousedown', e => {\n");
        sb.append("    const r = canvas.getBoundingClientRect();\n");
        sb.append("    const x = e.clientX - r.left, y = e.clientY - r.top;\n");
        sb.append("    const hit = getNodeAt(x, y);\n");
        sb.append("    if (hit) {\n");
        sb.append("      draggedNode = hit;\n");
        sb.append("      selectNode(hit);\n");
        sb.append("    } else {\n");
        sb.append("      isDragging = true;\n");
        sb.append("      dragStartX = e.clientX - panX;\n");
        sb.append("      dragStartY = e.clientY - panY;\n");
        sb.append("    }\n");
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
        sb.append("      if (hit) {\n");
        sb.append("        tooltip.style.display = 'block';\n");
        sb.append("        tooltip.style.left = (e.clientX + 14) + 'px';\n");
        sb.append("        tooltip.style.top = (e.clientY + 14) + 'px';\n");
        sb.append("        tooltip.innerHTML = `<strong>${hit.label}</strong><br><span style=\"color:#94a3b8; font-size:10px;\">${hit.pkg}</span>${hit.arch ? '<br><span style=\"color:#34d399; font-size:10px;\">' + hit.arch.text + '</span>' : ''}`;\n");
        sb.append("      } else {\n");
        sb.append("        tooltip.style.display = 'none';\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("  });\n");
        sb.append("  window.addEventListener('mouseup', () => { isDragging = false; draggedNode = null; });\n");
        sb.append("  canvas.addEventListener('wheel', e => {\n");
        sb.append("    e.preventDefault();\n");
        sb.append("    const factor = e.deltaY < 0 ? 1.15 : 0.87;\n");
        sb.append("    zoom = Math.max(0.15, Math.min(4.0, zoom * factor));\n");
        sb.append("  }, { passive: false });\n");
        sb.append("  document.getElementById('search-input').addEventListener('input', e => {\n");
        sb.append("    searchQuery = e.target.value.trim().toLowerCase();\n");
        sb.append("  });\n");
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
        sb.append("    document.getElementById('btn-pause').textContent = physicsRunning ? '⏸ Pause Physics' : '▶ Resume Physics';\n");
        sb.append("  });\n");
        sb.append("  document.getElementById('btn-pojo').addEventListener('click', () => {\n");
        sb.append("    hidePojos = !hidePojos;\n");
        sb.append("    document.getElementById('btn-pojo').classList.toggle('active', hidePojos);\n");
        sb.append("    document.getElementById('btn-pojo').textContent = hidePojos ? '⚡ Show POJOs' : '⚡ Hide POJOs';\n");
        sb.append("  });\n");
        sb.append("  window.addEventListener('resize', resize);\n");
        sb.append("  loadGraphData();\n");
        sb.append("  resize();\n");
        sb.append("  loop();\n");
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
