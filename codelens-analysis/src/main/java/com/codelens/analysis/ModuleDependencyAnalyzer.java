package com.codelens.analysis;

import com.codelens.core.model.CodeField;
import com.codelens.core.model.CodeMethod;
import com.codelens.core.model.CodePackage;
import com.codelens.core.model.CodeRelationship;
import com.codelens.core.model.CodeType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes deep dependency insights between modules in a codebase, including:
 * 1. Intermodular function calls (CALLS).
 * 2. Intermodular class usage (source class -> target class interactions).
 * 3. Touch point breakdowns by kind (CALLS, READS_FIELD, WRITES_FIELD, EXTENDS, IMPLEMENTS).
 * 4. Coupling and stability metrics (Afferent Ca, Efferent Ce, Instability I = Ce / (Ca + Ce)).
 */
public class ModuleDependencyAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ModuleDependencyAnalyzer.class);

    // ─────────────────────────────────────────────────────────────────────────
    // Models
    // ─────────────────────────────────────────────────────────────────────────

    public static class TouchPointDetail {
        public String fromEntity;
        public String toEntity;
        public String fromType;
        public String toType;
        public String kind;
        public int sourceLine;

        public TouchPointDetail() {}

        public TouchPointDetail(String fromEntity, String toEntity, String fromType, String toType, String kind, int sourceLine) {
            this.fromEntity = fromEntity;
            this.toEntity   = toEntity;
            this.fromType   = fromType;
            this.toType     = toType;
            this.kind       = kind;
            this.sourceLine = sourceLine;
        }
    }

    public static class ClassUsageSummary {
        public String sourceClassFqn;
        public String sourceClassSimpleName;
        public String targetClassFqn;
        public String targetClassSimpleName;
        public int touchPointCount;
        public Map<String, Integer> kinds = new LinkedHashMap<>();
        public List<TouchPointDetail> touchPoints = new ArrayList<>();

        public ClassUsageSummary() {}

        public ClassUsageSummary(String sourceClassFqn, String targetClassFqn) {
            this.sourceClassFqn = sourceClassFqn;
            this.sourceClassSimpleName = extractSimple(sourceClassFqn);
            this.targetClassFqn = targetClassFqn;
            this.targetClassSimpleName = extractSimple(targetClassFqn);
        }
    }

    public static class ConnectedModule {
        public String moduleName;
        public String packageFqn;
        public int totalTouchPoints;
        public int functionCallCount;
        public int classUsageCount;
        public boolean isExternal = false;
        public Map<String, Integer> kinds = new LinkedHashMap<>();
        public List<ClassUsageSummary> classUsages = new ArrayList<>();
        public List<TouchPointDetail> touchPoints = new ArrayList<>();

        public ConnectedModule() {}

        public ConnectedModule(String moduleName, String packageFqn) {
            this.moduleName = moduleName;
            this.packageFqn = packageFqn;
        }
    }

    public static class ModuleDependencyInsights {
        public String moduleName;
        public String packageFqn;
        public int fileCount;
        public int typeCount;

        // Metrics
        public int afferentCoupling;        // Ca: Inbound modules
        public int efferentCoupling;        // Ce: Outbound modules
        public int totalTouchPoints;        // Inbound + Outbound (codebase modules)
        public int totalInboundTouchPoints;
        public int totalOutboundTouchPoints;
        public int internalTouchPoints;     // Intra-module
        public double instability;          // Ce / (Ca + Ce)
        public String stabilityRating;      // Stable Core / Balanced / High Efferent

        // Kinds
        public Map<String, Integer> totalByKind    = new LinkedHashMap<>();
        public Map<String, Integer> inboundByKind  = new LinkedHashMap<>();
        public Map<String, Integer> outboundByKind = new LinkedHashMap<>();

        // Connected modules
        public List<ConnectedModule> outgoingModules = new ArrayList<>();
        public List<ConnectedModule> incomingModules = new ArrayList<>();
        public List<ConnectedModule> externalDependencies = new ArrayList<>();
        public List<ClassUsageSummary> topClassUsages = new ArrayList<>();
    }

    public static class ModuleOverviewItem {
        public String moduleName;
        public String packageFqn;
        public int typeCount;
        public int fileCount;
        public int afferentCoupling;
        public int efferentCoupling;
        public int totalTouchPoints;
        public int totalInboundTouchPoints;
        public int totalOutboundTouchPoints;
        public double instability;
        public String stabilityRating;
        public Map<String, Integer> touchPointsByKind = new LinkedHashMap<>();
        public List<String> topOutgoingModules = new ArrayList<>();
        public List<String> topIncomingModules = new ArrayList<>();
    }

    public static class ModuleOverviewPayload {
        public int totalModules;
        public int totalInterModuleTouchPoints;
        public Map<String, Integer> totalByKind = new LinkedHashMap<>();
        public List<ModuleOverviewItem> modules = new ArrayList<>();
    }

    public static class FullModuleDependencyResult {
        public ModuleOverviewPayload overview;
        public Map<String, ModuleDependencyInsights> insightsByModule;
        public Map<String, ModuleDependencyInsights> insightsByPackage;

        public FullModuleDependencyResult() {
            this.overview = new ModuleOverviewPayload();
            this.insightsByModule = new LinkedHashMap<>();
            this.insightsByPackage = new LinkedHashMap<>();
        }

        public FullModuleDependencyResult(ModuleOverviewPayload overview,
                                          Map<String, ModuleDependencyInsights> insightsByModule,
                                          Map<String, ModuleDependencyInsights> insightsByPackage) {
            this.overview = overview != null ? overview : new ModuleOverviewPayload();
            this.insightsByModule = insightsByModule != null ? insightsByModule : Collections.emptyMap();
            this.insightsByPackage = insightsByPackage != null ? insightsByPackage : Collections.emptyMap();
        }
    }

    @FunctionalInterface
    public interface EdgeConsumer {
        void accept(String fromEntity, String toEntity, String kind, int sourceLine);
    }

    public static final int MAX_SAMPLE_TOUCHPOINTS_PER_MODULE = Integer.MAX_VALUE;
    public static final int MAX_SAMPLE_TOUCHPOINTS_PER_CLASS_USAGE = Integer.MAX_VALUE;

    // ─────────────────────────────────────────────────────────────────────────
    // Analysis Methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Clean leading tilde (~), whitespace and extract clean FQN.
     */
    public static String cleanFqn(String fqn) {
        if (fqn == null) return "";
        String s = fqn.trim();
        while (s.startsWith("~")) s = s.substring(1);
        return s;
    }

    private void forEachEdge(List<CodeRelationship> relationships,
                            CallGraphAnalyzer callGraph,
                            EdgeConsumer consumer) {
        if (callGraph != null && callGraph.getCallGraph() != null) {
            org.jgrapht.Graph<String, org.jgrapht.graph.DefaultEdge> g = callGraph.getCallGraph();
            for (org.jgrapht.graph.DefaultEdge e : g.edgeSet()) {
                try {
                    String src = g.getEdgeSource(e);
                    String tgt = g.getEdgeTarget(e);
                    if (src != null && tgt != null) {
                        consumer.accept(src, tgt, "CALLS", 0);
                    }
                } catch (Throwable t) {
                    log.warn("Skipping failed edge in module analysis: {}", t.getMessage());
                }
            }
            if (relationships != null) {
                for (CodeRelationship rel : relationships) {
                    if (rel == null) continue;
                    if ("CALLS".equalsIgnoreCase(rel.getKind())) continue;
                    try {
                        consumer.accept(rel.getFromEntityFqn(), rel.getToEntityFqn(), rel.getKind(), rel.getSourceLine());
                    } catch (Throwable t) {
                        log.warn("Skipping failed relationship in module analysis: {}", t.getMessage());
                    }
                }
            }
        } else if (relationships != null) {
            for (CodeRelationship rel : relationships) {
                if (rel == null) continue;
                try {
                    consumer.accept(rel.getFromEntityFqn(), rel.getToEntityFqn(), rel.getKind(), rel.getSourceLine());
                } catch (Throwable t) {
                    log.warn("Skipping failed relationship in module analysis: {}", t.getMessage());
                }
            }
        }
    }

    /**
     * Unified single-pass analysis computing ModuleOverviewPayload AND deep ModuleDependencyInsights
     * for all modules and packages simultaneously without quadratic full-graph rescans.
     */
    public FullModuleDependencyResult analyzeAllModules(List<CodePackage> packages,
                                                        List<CodeType> types,
                                                        List<CodeMethod> methods,
                                                        List<CodeField> fields,
                                                        List<CodeRelationship> relationships,
                                                        CallGraphAnalyzer callGraph) {
        AnalysisContext ctx = buildContext(packages, types, methods, fields);
        ModuleOverviewPayload overview = new ModuleOverviewPayload();

        Map<String, ModuleOverviewItem> moduleMap = new LinkedHashMap<>();
        Map<String, ModuleDependencyInsights> insightsByModule = new LinkedHashMap<>();

        Map<String, Integer> typesCountPerModule = new HashMap<>();
        if (types != null) {
            for (CodeType t : types) {
                if (t == null || t.getFqn() == null) continue;
                try {
                    String mod = ctx.getModuleForType(t.getFqn());
                    if (mod != null) {
                        typesCountPerModule.merge(mod.toLowerCase(), 1, Integer::sum);
                    }
                } catch (Throwable t2) {
                    log.warn("Skipping failed type in module counting: {}", t2.getMessage());
                }
            }
        }

        for (String mod : ctx.allModules) {
            String modLower = mod.toLowerCase();
            String primaryPkg = ctx.moduleToPrimaryPackage.getOrDefault(mod, mod);
            CodePackage pkg = ctx.packageByFqn.get(primaryPkg);
            int fileCount = pkg != null ? pkg.getFileCount() : 0;
            int typeCount = pkg != null ? pkg.getTypeCount() : typesCountPerModule.getOrDefault(modLower, 0);

            ModuleOverviewItem ovItem = new ModuleOverviewItem();
            ovItem.moduleName = mod;
            ovItem.packageFqn = primaryPkg;
            ovItem.fileCount = fileCount;
            ovItem.typeCount = typeCount;
            moduleMap.put(mod, ovItem);

            ModuleDependencyInsights mi = new ModuleDependencyInsights();
            mi.moduleName = mod;
            mi.packageFqn = primaryPkg;
            mi.fileCount = fileCount;
            mi.typeCount = typeCount;
            insightsByModule.put(modLower, mi);
        }

        Map<String, Map<String, Integer>> outMap = new HashMap<>();
        Map<String, Map<String, Integer>> inMap = new HashMap<>();

        Map<String, Map<String, ConnectedModule>> outgoingMapByMod = new HashMap<>();
        Map<String, Map<String, ConnectedModule>> incomingMapByMod = new HashMap<>();
        Map<String, Map<String, ConnectedModule>> externalMapByMod = new HashMap<>();
        Map<String, Map<String, Map<String, ClassUsageSummary>>> outgoingClassUsageByMod = new HashMap<>();
        Map<String, Map<String, Map<String, ClassUsageSummary>>> incomingClassUsageByMod = new HashMap<>();

        forEachEdge(relationships, callGraph, (fromEntity, toEntity, kind, line) -> {
            String srcFqn = cleanFqn(fromEntity);
            String tgtFqn = cleanFqn(toEntity);

            String srcMod = ctx.getModuleForEntity(srcFqn);
            String tgtMod = ctx.getModuleForEntity(tgtFqn);

            if (srcMod == null || tgtMod == null) return;

            String srcModLower = srcMod.toLowerCase();
            String tgtModLower = tgtMod.toLowerCase();

            if (srcModLower.equals(tgtModLower)) {
                ModuleDependencyInsights mi = insightsByModule.get(srcModLower);
                if (mi != null) mi.internalTouchPoints++;
                return;
            }

            boolean isSrcProject = ctx.isProjectModule(srcMod);
            boolean isTgtProject = ctx.isProjectModule(tgtMod);

            if (isSrcProject && isTgtProject) {
                overview.totalInterModuleTouchPoints++;
                increment(overview.totalByKind, kind);

                ModuleOverviewItem srcOv = moduleMap.computeIfAbsent(srcMod, k -> {
                    ModuleOverviewItem m = new ModuleOverviewItem();
                    m.moduleName = k;
                    m.packageFqn = ctx.moduleToPrimaryPackage.getOrDefault(k, k);
                    return m;
                });
                srcOv.totalOutboundTouchPoints++;
                srcOv.totalTouchPoints++;
                increment(srcOv.touchPointsByKind, kind);
                outMap.computeIfAbsent(srcMod, k -> new HashMap<>()).merge(tgtMod, 1, Integer::sum);

                ModuleOverviewItem tgtOv = moduleMap.computeIfAbsent(tgtMod, k -> {
                    ModuleOverviewItem m = new ModuleOverviewItem();
                    m.moduleName = k;
                    m.packageFqn = ctx.moduleToPrimaryPackage.getOrDefault(k, k);
                    return m;
                });
                tgtOv.totalInboundTouchPoints++;
                tgtOv.totalTouchPoints++;
                increment(tgtOv.touchPointsByKind, kind);
                inMap.computeIfAbsent(tgtMod, k -> new HashMap<>()).merge(srcMod, 1, Integer::sum);
            }

            String srcType = ctx.getTypeForEntity(srcFqn);
            String tgtType = ctx.getTypeForEntity(tgtFqn);

            // Update source module deep insights
            if (isSrcProject) {
                ModuleDependencyInsights miSrc = insightsByModule.get(srcModLower);
                if (miSrc != null) {
                    if (!isTgtProject) {
                        ConnectedModule cm = externalMapByMod.computeIfAbsent(srcModLower, k -> new LinkedHashMap<>())
                            .computeIfAbsent(tgtMod, k -> {
                                ConnectedModule m = new ConnectedModule(k, tgtType != null ? tgtType : k);
                                m.isExternal = true;
                                return m;
                            });
                        cm.totalTouchPoints++;
                        if ("CALLS".equalsIgnoreCase(kind)) cm.functionCallCount++;
                        increment(cm.kinds, kind);
                        cm.touchPoints.add(new TouchPointDetail(srcFqn, tgtFqn, srcType, tgtType, kind, line));
                    } else {
                        miSrc.totalOutboundTouchPoints++;
                        miSrc.totalTouchPoints++;
                        increment(miSrc.outboundByKind, kind);
                        increment(miSrc.totalByKind, kind);

                        ConnectedModule cm = outgoingMapByMod.computeIfAbsent(srcModLower, k -> new LinkedHashMap<>())
                            .computeIfAbsent(tgtMod, k -> new ConnectedModule(k, ctx.moduleToPrimaryPackage.getOrDefault(k, k)));
                        cm.totalTouchPoints++;
                        if ("CALLS".equalsIgnoreCase(kind)) cm.functionCallCount++;
                        increment(cm.kinds, kind);
                        cm.touchPoints.add(new TouchPointDetail(srcFqn, tgtFqn, srcType, tgtType, kind, line));

                        if (srcType != null && tgtType != null && !srcType.equals(tgtType)) {
                            String classPairKey = srcType + "->" + tgtType;
                            ClassUsageSummary cus = outgoingClassUsageByMod.computeIfAbsent(srcModLower, k -> new LinkedHashMap<>())
                                .computeIfAbsent(tgtMod, k -> new LinkedHashMap<>())
                                .computeIfAbsent(classPairKey, k -> new ClassUsageSummary(srcType, tgtType));
                            cus.touchPointCount++;
                            increment(cus.kinds, kind);
                            cus.touchPoints.add(new TouchPointDetail(srcFqn, tgtFqn, srcType, tgtType, kind, line));
                        }
                    }
                }
            }

            // Update target module deep insights
            if (isTgtProject && isSrcProject) {
                ModuleDependencyInsights miTgt = insightsByModule.get(tgtModLower);
                if (miTgt != null) {
                    miTgt.totalInboundTouchPoints++;
                    miTgt.totalTouchPoints++;
                    increment(miTgt.inboundByKind, kind);
                    increment(miTgt.totalByKind, kind);

                    ConnectedModule cm = incomingMapByMod.computeIfAbsent(tgtModLower, k -> new LinkedHashMap<>())
                        .computeIfAbsent(srcMod, k -> new ConnectedModule(k, ctx.moduleToPrimaryPackage.getOrDefault(k, k)));
                    cm.totalTouchPoints++;
                    if ("CALLS".equalsIgnoreCase(kind)) cm.functionCallCount++;
                    increment(cm.kinds, kind);
                    cm.touchPoints.add(new TouchPointDetail(srcFqn, tgtFqn, srcType, tgtType, kind, line));

                    if (srcType != null && tgtType != null && !srcType.equals(tgtType)) {
                        String classPairKey = srcType + "->" + tgtType;
                        ClassUsageSummary cus = incomingClassUsageByMod.computeIfAbsent(tgtModLower, k -> new LinkedHashMap<>())
                            .computeIfAbsent(srcMod, k -> new LinkedHashMap<>())
                            .computeIfAbsent(classPairKey, k -> new ClassUsageSummary(srcType, tgtType));
                        cus.touchPointCount++;
                        increment(cus.kinds, kind);
                        cus.touchPoints.add(new TouchPointDetail(srcFqn, tgtFqn, srcType, tgtType, kind, line));
                    }
                }
            }
        });

        // Finalize overview modules
        for (Map.Entry<String, ModuleOverviewItem> entry : moduleMap.entrySet()) {
            String mod = entry.getKey();
            ModuleOverviewItem item = entry.getValue();

            Map<String, Integer> outs = outMap.getOrDefault(mod, Collections.emptyMap());
            Map<String, Integer> ins = inMap.getOrDefault(mod, Collections.emptyMap());

            item.efferentCoupling = outs.size();
            item.afferentCoupling = ins.size();

            int totalC = item.efferentCoupling + item.afferentCoupling;
            item.instability = totalC > 0 ? Math.round(((double) item.efferentCoupling / totalC) * 100.0) / 100.0 : 0.0;

            if (item.afferentCoupling >= 3 && item.efferentCoupling <= 1) {
                item.stabilityRating = "Stable Core";
            } else if (item.instability <= 0.3) {
                item.stabilityRating = "Stable";
            } else if (item.instability >= 0.7) {
                item.stabilityRating = "Flexible";
            } else {
                item.stabilityRating = "Balanced";
            }

            item.topOutgoingModules = outs.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                .collect(Collectors.toList());

            item.topIncomingModules = ins.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                .collect(Collectors.toList());

            overview.modules.add(item);
        }
        overview.modules.sort((a, b) -> Integer.compare(b.totalTouchPoints, a.totalTouchPoints));
        overview.totalModules = overview.modules.size();

        // Finalize each module insights
        for (Map.Entry<String, ModuleDependencyInsights> entry : insightsByModule.entrySet()) {
            String modLower = entry.getKey();
            ModuleDependencyInsights insights = entry.getValue();

            // Outgoing modules
            Map<String, ConnectedModule> outModMap = outgoingMapByMod.getOrDefault(modLower, Collections.emptyMap());
            Map<String, Map<String, ClassUsageSummary>> outClassMap = outgoingClassUsageByMod.getOrDefault(modLower, Collections.emptyMap());
            for (Map.Entry<String, ConnectedModule> e : outModMap.entrySet()) {
                ConnectedModule cm = e.getValue();
                Map<String, ClassUsageSummary> cMap = outClassMap.get(e.getKey());
                if (cMap != null) {
                    cm.classUsages = new ArrayList<>(cMap.values());
                    cm.classUsages.sort((a, b) -> Integer.compare(b.touchPointCount, a.touchPointCount));
                    cm.classUsageCount = (int) cm.classUsages.stream().map(c -> c.targetClassFqn).distinct().count();
                }
                insights.outgoingModules.add(cm);
            }
            insights.outgoingModules.sort((a, b) -> Integer.compare(b.totalTouchPoints, a.totalTouchPoints));

            // Incoming modules
            Map<String, ConnectedModule> inModMap = incomingMapByMod.getOrDefault(modLower, Collections.emptyMap());
            Map<String, Map<String, ClassUsageSummary>> inClassMap = incomingClassUsageByMod.getOrDefault(modLower, Collections.emptyMap());
            for (Map.Entry<String, ConnectedModule> e : inModMap.entrySet()) {
                ConnectedModule cm = e.getValue();
                Map<String, ClassUsageSummary> cMap = inClassMap.get(e.getKey());
                if (cMap != null) {
                    cm.classUsages = new ArrayList<>(cMap.values());
                    cm.classUsages.sort((a, b) -> Integer.compare(b.touchPointCount, a.touchPointCount));
                    cm.classUsageCount = (int) cm.classUsages.stream().map(c -> c.sourceClassFqn).distinct().count();
                }
                insights.incomingModules.add(cm);
            }
            insights.incomingModules.sort((a, b) -> Integer.compare(b.totalTouchPoints, a.totalTouchPoints));

            // External dependencies
            Map<String, ConnectedModule> extModMap = externalMapByMod.getOrDefault(modLower, Collections.emptyMap());
            insights.externalDependencies = new ArrayList<>(extModMap.values());
            insights.externalDependencies.sort((a, b) -> Integer.compare(b.totalTouchPoints, a.totalTouchPoints));

            // Top class usages overall for this module
            List<ClassUsageSummary> allClassUsages = new ArrayList<>();
            for (ConnectedModule cm : insights.outgoingModules) {
                allClassUsages.addAll(cm.classUsages);
            }
            allClassUsages.sort((a, b) -> Integer.compare(b.touchPointCount, a.touchPointCount));
            insights.topClassUsages = allClassUsages.stream().limit(15).collect(Collectors.toList());

            // Coupling & instability
            insights.afferentCoupling = insights.incomingModules.size();
            insights.efferentCoupling = insights.outgoingModules.size();
            int totalC = insights.afferentCoupling + insights.efferentCoupling;
            insights.instability = totalC > 0 ? Math.round(((double) insights.efferentCoupling / totalC) * 100.0) / 100.0 : 0.0;

            if (insights.afferentCoupling >= 3 && insights.efferentCoupling <= 1) {
                insights.stabilityRating = "Stable Core (High Afferent)";
            } else if (insights.instability <= 0.3) {
                insights.stabilityRating = "Highly Stable";
            } else if (insights.instability >= 0.7) {
                insights.stabilityRating = "Flexible / High Efferent";
            } else {
                insights.stabilityRating = "Balanced";
            }
        }

        // Map packages to their module's insights
        Map<String, ModuleDependencyInsights> insightsByPackage = new LinkedHashMap<>();
        if (packages != null) {
            for (CodePackage p : packages) {
                String mod = ctx.packageToModule.get(p.getFqn());
                if (mod == null) mod = ctx.findModuleByPackagePrefix(p.getFqn());
                if (mod == null) mod = CallGraphAnalyzer.extractModuleName(p.getFqn());
                if (mod != null) {
                    ModuleDependencyInsights mi = insightsByModule.get(mod.toLowerCase());
                    if (mi != null) {
                        insightsByPackage.put(p.getFqn().toLowerCase(), mi);
                    }
                }
            }
        }

        return new FullModuleDependencyResult(overview, insightsByModule, insightsByPackage);
    }

    /**
     * Analyze module dependencies and touch points for a specific module or package.
     */
    public ModuleDependencyInsights analyzeModule(String targetModuleOrPkg,
                                                  List<CodePackage> packages,
                                                  List<CodeType> types,
                                                  List<CodeMethod> methods,
                                                  List<CodeField> fields,
                                                  List<CodeRelationship> relationships) {
        return analyzeModule(targetModuleOrPkg, packages, types, methods, fields, relationships, null);
    }

    public ModuleDependencyInsights analyzeModule(String targetModuleOrPkg,
                                                  List<CodePackage> packages,
                                                  List<CodeType> types,
                                                  List<CodeMethod> methods,
                                                  List<CodeField> fields,
                                                  List<CodeRelationship> relationships,
                                                  CallGraphAnalyzer callGraph) {
        if (targetModuleOrPkg == null || targetModuleOrPkg.isBlank()) {
            return null;
        }

        FullModuleDependencyResult result = analyzeAllModules(packages, types, methods, fields, relationships, callGraph);
        String q = targetModuleOrPkg.trim().toLowerCase();
        ModuleDependencyInsights insights = result.insightsByModule.get(q);
        if (insights != null) return insights;
        insights = result.insightsByPackage.get(q);
        if (insights != null) return insights;

        // Try prefix match on package
        for (Map.Entry<String, ModuleDependencyInsights> entry : result.insightsByPackage.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(q) || entry.getKey().startsWith(q + ".") || q.startsWith(entry.getKey() + ".")) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * Compute overview metrics and inter-module touch point matrix across all modules in the codebase.
     */
    public ModuleOverviewPayload analyzeAll(List<CodePackage> packages,
                                            List<CodeType> types,
                                            List<CodeMethod> methods,
                                            List<CodeField> fields,
                                            List<CodeRelationship> relationships) {
        return analyzeAll(packages, types, methods, fields, relationships, null);
    }

    public ModuleOverviewPayload analyzeAll(List<CodePackage> packages,
                                            List<CodeType> types,
                                            List<CodeMethod> methods,
                                            List<CodeField> fields,
                                            List<CodeRelationship> relationships,
                                            CallGraphAnalyzer callGraph) {
        return analyzeAllModules(packages, types, methods, fields, relationships, callGraph).overview;
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Internal Helper Context
    // ─────────────────────────────────────────────────────────────────────────

    private AnalysisContext buildContext(List<CodePackage> packages,
                                         List<CodeType> types,
                                         List<CodeMethod> methods,
                                         List<CodeField> fields) {
        AnalysisContext ctx = new AnalysisContext();

        if (packages != null) {
            for (CodePackage p : packages) {
                if (p == null || p.getFqn() == null) continue;
                try {
                    ctx.packageByFqn.put(p.getFqn(), p);
                    String mod = (p.getName() != null && !p.getName().isBlank()) ? p.getName() : CallGraphAnalyzer.extractModuleName(p.getFqn());
                    ctx.packageToModule.put(p.getFqn(), mod);
                    ctx.moduleToPrimaryPackage.putIfAbsent(mod, p.getFqn());
                    ctx.allModules.add(mod);
                } catch (Throwable t) {
                    log.warn("Skipping malformed package in module context: {}", t.getMessage());
                }
            }
        }

        if (types != null) {
            for (CodeType t : types) {
                if (t == null || t.getFqn() == null) continue;
                try {
                    ctx.typeMap.put(t.getFqn(), t);
                    String pkg = t.getPackageFqn() != null ? t.getPackageFqn() : CallGraphAnalyzer.extractPackageFqn(t.getFqn());
                    ctx.typeToPkg.put(t.getFqn(), pkg);
                    if (t.getSimpleName() != null && !t.getSimpleName().isBlank()) {
                        ctx.simpleNameToType.putIfAbsent(t.getSimpleName(), t.getFqn());
                        ctx.simpleNameToType.putIfAbsent(t.getSimpleName().toLowerCase(), t.getFqn());
                    }

                    String mod = ctx.packageToModule.get(pkg);
                    if (mod == null) {
                        mod = ctx.findModuleByPackagePrefix(pkg);
                        if (mod == null) {
                            mod = CallGraphAnalyzer.extractModuleName(t.getFqn());
                        }
                        ctx.packageToModule.put(pkg, mod);
                        ctx.moduleToPrimaryPackage.putIfAbsent(mod, pkg);
                        ctx.allModules.add(mod);
                    }
                } catch (Throwable err) {
                    log.warn("Skipping malformed type in module context: {}", err.getMessage());
                }
            }
        }

        if (methods != null) {
            for (CodeMethod m : methods) {
                if (m == null || m.getFqn() == null) continue;
                try {
                    ctx.methodToType.put(m.getFqn(), m.getDeclaringTypeFqn());
                    ctx.methodToType.put(m.getFqn().toLowerCase(), m.getDeclaringTypeFqn());
                    int paren = m.getFqn().indexOf('(');
                    if (paren > 0) {
                        String noParams = m.getFqn().substring(0, paren);
                        ctx.methodToType.put(noParams, m.getDeclaringTypeFqn());
                        ctx.methodToType.put(noParams.toLowerCase(), m.getDeclaringTypeFqn());
                    }
                    if (m.getSimpleName() != null && !m.getSimpleName().isBlank() && m.getDeclaringTypeFqn() != null) {
                        String simpleClass = extractSimple(m.getDeclaringTypeFqn());
                        ctx.methodToType.put(simpleClass + "." + m.getSimpleName(), m.getDeclaringTypeFqn());
                        ctx.methodToType.put((simpleClass + "." + m.getSimpleName()).toLowerCase(), m.getDeclaringTypeFqn());
                    }
                } catch (Throwable err) {
                    log.warn("Skipping malformed method in module context: {}", err.getMessage());
                }
            }
        }

        if (fields != null) {
            for (CodeField f : fields) {
                if (f == null || f.getFqn() == null) continue;
                try {
                    ctx.fieldToType.put(f.getFqn(), f.getDeclaringTypeFqn());
                    ctx.fieldToType.put(f.getFqn().toLowerCase(), f.getDeclaringTypeFqn());
                    if (f.getSimpleName() != null && !f.getSimpleName().isBlank() && f.getDeclaringTypeFqn() != null) {
                        String simpleClass = extractSimple(f.getDeclaringTypeFqn());
                        ctx.fieldToType.put(simpleClass + "." + f.getSimpleName(), f.getDeclaringTypeFqn());
                        ctx.fieldToType.put((simpleClass + "." + f.getSimpleName()).toLowerCase(), f.getDeclaringTypeFqn());
                    }
                } catch (Throwable err) {
                    log.warn("Skipping malformed field in module context: {}", err.getMessage());
                }
            }
        }

        return ctx;
    }

    private static void increment(Map<String, Integer> map, String key) {
        if (key == null) return;
        map.merge(key, 1, Integer::sum);
    }

    private static String extractSimple(String fqn) {
        if (fqn == null || fqn.isBlank()) return "";
        int paren = fqn.indexOf('(');
        String base = (paren > 0) ? fqn.substring(0, paren) : fqn;
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot + 1) : base;
    }

    private static class AnalysisContext {
        final Map<String, CodePackage> packageByFqn = new HashMap<>();
        final Map<String, String> packageToModule   = new HashMap<>();
        final Map<String, String> moduleToPrimaryPackage = new HashMap<>();
        final Set<String> allModules = new LinkedHashSet<>();

        final Map<String, CodeType> typeMap = new HashMap<>();
        final Map<String, String> typeToPkg = new HashMap<>();
        final Map<String, String> methodToType = new HashMap<>();
        final Map<String, String> fieldToType = new HashMap<>();
        final Map<String, String> simpleNameToType = new HashMap<>();

        boolean isProjectModule(String mod) {
            if (mod == null || mod.isBlank() || "default".equalsIgnoreCase(mod)) return false;
            if ("java".equalsIgnoreCase(mod) || "javax".equalsIgnoreCase(mod) || "sun".equalsIgnoreCase(mod) || "jdk".equalsIgnoreCase(mod)) return false;
            for (String m : allModules) {
                if (m.equalsIgnoreCase(mod)) return true;
            }
            return false;
        }

        String canonicalModule(String mod) {
            if (mod == null) return null;
            for (String m : allModules) {
                if (m.equalsIgnoreCase(mod)) return m;
            }
            return mod;
        }

        String findModuleByPackagePrefix(String target) {
            if (target == null || target.isBlank()) return null;
            String bestPkg = null;
            for (String p : packageToModule.keySet()) {
                if (target.startsWith(p + ".") || target.equals(p)) {
                    if (bestPkg == null || p.length() > bestPkg.length()) {
                        bestPkg = p;
                    }
                }
            }
            return (bestPkg != null) ? packageToModule.get(bestPkg) : null;
        }

        String resolveTargetModule(String query) {
            if (query == null || query.isBlank()) return null;
            String trimmed = query.trim();

            // 1. Direct match on module name
            for (String mod : allModules) {
                if (mod.equalsIgnoreCase(trimmed)) return mod;
            }

            // 2. Direct match on package FQN
            if (packageToModule.containsKey(trimmed)) {
                return packageToModule.get(trimmed);
            }

            // 3. Prefix match against packageToModule
            String prefixMod = findModuleByPackagePrefix(trimmed);
            if (prefixMod != null) return prefixMod;

            // 4. Leaf package segment match
            for (Map.Entry<String, String> entry : packageToModule.entrySet()) {
                String pkg = entry.getKey();
                if (pkg.equalsIgnoreCase(trimmed) || pkg.endsWith("." + trimmed)) {
                    return entry.getValue();
                }
            }

            // 5. Fallback: extractModuleName
            String mod = CallGraphAnalyzer.extractModuleName(trimmed);
            for (String m : allModules) {
                if (m.equalsIgnoreCase(mod)) return m;
            }
            return mod;
        }

        // Memoization caches for fast high-throughput edge traversal
        private final Map<String, String> entityToTypeCache = new HashMap<>(8192);
        private final Map<String, String> entityToModuleCache = new HashMap<>(8192);

        String getTypeForEntity(String entityFqn) {
            if (entityFqn == null || entityFqn.isBlank()) return null;
            String cached = entityToTypeCache.get(entityFqn);
            if (cached != null) return cached.isEmpty() ? null : cached;

            String res = resolveTypeForEntity(entityFqn);
            entityToTypeCache.put(entityFqn, res != null ? res : "");
            return res;
        }

        private String resolveTypeForEntity(String entityFqn) {
            String cleaned = cleanFqn(entityFqn);

            if (typeMap.containsKey(cleaned)) return cleaned;
            if (methodToType.containsKey(cleaned)) return methodToType.get(cleaned);
            if (fieldToType.containsKey(cleaned)) return fieldToType.get(cleaned);
            if (simpleNameToType.containsKey(cleaned)) return simpleNameToType.get(cleaned);

            String lower = cleaned.toLowerCase();
            if (methodToType.containsKey(lower)) return methodToType.get(lower);
            if (fieldToType.containsKey(lower)) return fieldToType.get(lower);
            if (simpleNameToType.containsKey(lower)) return simpleNameToType.get(lower);

            int paren = cleaned.indexOf('(');
            String base = (paren > 0) ? cleaned.substring(0, paren) : cleaned;
            if (methodToType.containsKey(base)) return methodToType.get(base);
            if (typeMap.containsKey(base)) return base;
            if (simpleNameToType.containsKey(base)) return simpleNameToType.get(base);

            String baseLower = base.toLowerCase();
            if (methodToType.containsKey(baseLower)) return methodToType.get(baseLower);
            if (simpleNameToType.containsKey(baseLower)) return simpleNameToType.get(baseLower);

            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                String potentialType = base.substring(0, dot);
                if (typeMap.containsKey(potentialType)) return potentialType;
                if (simpleNameToType.containsKey(potentialType)) return simpleNameToType.get(potentialType);
                if (simpleNameToType.containsKey(potentialType.toLowerCase())) return simpleNameToType.get(potentialType.toLowerCase());

                // Check simple class name of potentialType (e.g. "com.tcs.bancs.RK.AuditTrailService" -> "AuditTrailService")
                int typeDot = potentialType.lastIndexOf('.');
                String simpleType = (typeDot >= 0) ? potentialType.substring(typeDot + 1) : potentialType;
                if (typeMap.containsKey(simpleType)) return simpleType;
                if (simpleNameToType.containsKey(simpleType)) return simpleNameToType.get(simpleType);
                if (simpleNameToType.containsKey(simpleType.toLowerCase())) return simpleNameToType.get(simpleType.toLowerCase());

                // Variable name resolution (e.g. "auditTrailService" -> "AuditTrailService")
                if (!simpleType.isEmpty() && Character.isLowerCase(simpleType.charAt(0))) {
                    String cap = Character.toUpperCase(simpleType.charAt(0)) + simpleType.substring(1);
                    if (simpleNameToType.containsKey(cap)) return simpleNameToType.get(cap);
                }

                // Check if base matches method or field
                if (methodToType.containsKey(base)) return methodToType.get(base);
                if (methodToType.containsKey(baseLower)) return methodToType.get(baseLower);
                if (fieldToType.containsKey(base)) return fieldToType.get(base);
                if (fieldToType.containsKey(baseLower)) return fieldToType.get(baseLower);

                return potentialType;
            }

            // Simple name without dot (e.g. "AuditTrailService" or "Worker")
            if (simpleNameToType.containsKey(base)) return simpleNameToType.get(base);
            if (!base.isEmpty() && Character.isLowerCase(base.charAt(0))) {
                String cap = Character.toUpperCase(base.charAt(0)) + base.substring(1);
                if (simpleNameToType.containsKey(cap)) return simpleNameToType.get(cap);
            }

            return base;
        }

        String getModuleForType(String typeFqn) {
            if (typeFqn == null) return null;
            String pkg = typeToPkg.get(typeFqn);
            if (pkg != null && packageToModule.containsKey(pkg)) {
                return packageToModule.get(pkg);
            }
            // Longest prefix match against packageToModule
            String bestMod = findModuleByPackagePrefix(typeFqn);
            if (bestMod != null) {
                return bestMod;
            }
            return CallGraphAnalyzer.extractModuleName(typeFqn);
        }

        String getModuleForEntity(String entityFqn) {
            if (entityFqn == null || entityFqn.isBlank()) return null;
            String cached = entityToModuleCache.get(entityFqn);
            if (cached != null) return cached.isEmpty() ? null : cached;

            String res = resolveModuleForEntity(entityFqn);
            entityToModuleCache.put(entityFqn, res != null ? res : "");
            return res;
        }

        private String resolveModuleForEntity(String entityFqn) {
            String cleaned = cleanFqn(entityFqn);

            String type = getTypeForEntity(cleaned);
            if (type != null) {
                String mod = getModuleForType(type);
                if (mod != null && isProjectModule(mod)) return canonicalModule(mod);
            }

            // Prefix match against packageToModule directly
            String prefixMod = findModuleByPackagePrefix(cleaned);
            if (prefixMod != null && isProjectModule(prefixMod)) return canonicalModule(prefixMod);

            String mod = CallGraphAnalyzer.extractModuleName(cleaned);
            if (isProjectModule(mod)) return canonicalModule(mod);
            return mod;
        }
    }
}
