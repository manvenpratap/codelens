package com.codelens.analysis;

import com.codelens.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects structural inconsistencies across the indexed codebase.
 *
 * Three detection passes:
 *
 *   1. DIVERGENT_SIGNATURE — methods sharing the same simple name across
 *      the same class or related types (inheritance/interface hierarchy)
 *      with different return types or param counts.
 *      NOTE: Methods with the same simple name in completely independent,
 *      unrelated classes are standard object-oriented design and are NOT
 *      flagged as divergent signatures.
 *
 *   2. SIMILAR_NAME — pairs of methods or fields in the same class
 *      whose names have Levenshtein distance ≤ 2 but whose types or bodies diverge.
 *      (Signals: copy-paste variations, naming drift within a class.)
 *
 *   3. SIMILAR_BODY — methods in the same class with identical or near-identical
 *      body hashes (SHA-256 prefix) but different names — possible duplication.
 *      (Signals: dead code, refactoring targets.)
 */
public class InconsistencyDetector {

    private static final Logger log = LoggerFactory.getLogger(InconsistencyDetector.class);
    private static final double NAME_SIMILARITY_THRESHOLD = 0.75;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Run all detection passes with full type hierarchy awareness.
     *
     * @param types         all types discovered in the scan
     * @param methods       all methods discovered in the scan
     * @param fields        all fields discovered in the scan
     * @param relationships all relationships (for EXTENDS and IMPLEMENTS hierarchy)
     */
    public List<InconsistencyReport> detect(List<CodeType> types,
                                            List<CodeMethod> methods,
                                            List<CodeField> fields,
                                            List<CodeRelationship> relationships) {
        if (methods == null) methods = Collections.emptyList();
        if (fields == null) fields = Collections.emptyList();

        Map<String, Set<String>> typeAncestors = buildTypeHierarchyMap(types, relationships);

        List<InconsistencyReport> reports = new ArrayList<>();
        reports.addAll(detectDivergentSignatures(methods, typeAncestors));
        reports.addAll(detectSimilarNames(methods, fields, typeAncestors));
        reports.addAll(detectSimilarBodies(methods));
        log.info("Inconsistency detection: {} issues found", reports.size());
        return reports;
    }

    public List<InconsistencyReport> detect(List<CodeType> types,
                                            List<CodeMethod> methods,
                                            List<CodeField> fields) {
        return detect(types, methods, fields, Collections.emptyList());
    }

    /**
     * Backward-compatible entrypoint without explicit type hierarchy.
     */
    public List<InconsistencyReport> detect(List<CodeMethod> methods,
                                            List<CodeField> fields) {
        return detect(Collections.emptyList(), methods, fields, Collections.emptyList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Type Hierarchy Resolution
    // ─────────────────────────────────────────────────────────────────────────

    private static final Set<String> IGNORED_ANCESTORS = Set.of(
        "object", "java.lang.object",
        "serializable", "java.io.serializable",
        "cloneable", "java.lang.cloneable",
        "comparable", "java.lang.comparable",
        "autocloseable", "java.lang.autocloseable",
        "closeable", "java.io.closeable"
    );

    private Map<String, Set<String>> buildTypeHierarchyMap(List<CodeType> types, List<CodeRelationship> relationships) {
        Map<String, Set<String>> directAncestors = new HashMap<>();

        if (types != null) {
            for (CodeType t : types) {
                if (t == null || t.getFqn() == null) continue;
                String fqnKey = t.getFqn().toLowerCase(Locale.ROOT).trim();
                Set<String> parents = directAncestors.computeIfAbsent(fqnKey, k -> new HashSet<>());

                if (t.getSuperClass() != null && !t.getSuperClass().isBlank()) {
                    String sc = t.getSuperClass().toLowerCase(Locale.ROOT).trim();
                    if (!IGNORED_ANCESTORS.contains(sc)) {
                        parents.add(sc);
                    }
                }
                if (t.getInterfaces() != null) {
                    for (String iface : t.getInterfaces()) {
                        if (iface != null && !iface.isBlank()) {
                            String ifKey = iface.toLowerCase(Locale.ROOT).trim();
                            if (!IGNORED_ANCESTORS.contains(ifKey)) {
                                parents.add(ifKey);
                            }
                        }
                    }
                }
            }
        }

        if (relationships != null) {
            for (CodeRelationship r : relationships) {
                if (r == null) continue;
                String kind = r.getKind();
                if (("EXTENDS".equalsIgnoreCase(kind) || "IMPLEMENTS".equalsIgnoreCase(kind))
                        && r.getFromEntityFqn() != null && r.getToEntityFqn() != null) {
                    String src = r.getFromEntityFqn().toLowerCase(Locale.ROOT).trim();
                    String tgt = r.getToEntityFqn().toLowerCase(Locale.ROOT).trim();
                    if (!IGNORED_ANCESTORS.contains(tgt)) {
                        directAncestors.computeIfAbsent(src, k -> new HashSet<>()).add(tgt);
                    }
                }
            }
        }

        // Compute transitive closure of ancestors for each type
        Map<String, Set<String>> allAncestors = new HashMap<>();
        for (String type : directAncestors.keySet()) {
            Set<String> visited = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>(directAncestors.getOrDefault(type, Collections.emptySet()));
            while (!queue.isEmpty()) {
                String parent = queue.poll();
                if (visited.add(parent)) {
                    Set<String> next = directAncestors.get(parent);
                    if (next != null) {
                        for (String n : next) {
                            if (!visited.contains(n)) queue.add(n);
                        }
                    }
                }
            }
            allAncestors.put(type, visited);
        }

        return allAncestors;
    }

    private boolean areTypesRelated(String type1, String type2, Map<String, Set<String>> allAncestors) {
        if (type1 == null || type2 == null) return false;
        String t1 = type1.toLowerCase(Locale.ROOT).trim();
        String t2 = type2.toLowerCase(Locale.ROOT).trim();
        if (t1.equals(t2)) return true; // Exact same class

        // Inner class relationship (e.g. Foo and Foo$Bar or Foo.Bar)
        if (t1.startsWith(t2 + "$") || t1.startsWith(t2 + ".") ||
            t2.startsWith(t1 + "$") || t2.startsWith(t1 + ".")) {
            return true;
        }

        if (allAncestors == null || allAncestors.isEmpty()) return false;

        Set<String> anc1 = allAncestors.getOrDefault(t1, Collections.emptySet());
        Set<String> anc2 = allAncestors.getOrDefault(t2, Collections.emptySet());

        String simple1 = t1.contains(".") ? t1.substring(t1.lastIndexOf('.') + 1) : t1;
        String simple2 = t2.contains(".") ? t2.substring(t2.lastIndexOf('.') + 1) : t2;

        // Subtyping: t1 directly or indirectly extends/implements t2 or vice-versa
        return anc1.contains(t2) || anc1.contains(simple2) ||
               anc2.contains(t1) || anc2.contains(simple1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pass 1 – Divergent signatures (scoped to same class or related type hierarchy)
    // ─────────────────────────────────────────────────────────────────────────

    private List<InconsistencyReport> detectDivergentSignatures(List<CodeMethod> methods,
                                                                Map<String, Set<String>> typeAncestors) {
        List<InconsistencyReport> out = new ArrayList<>();

        // Group methods by simple name
        Map<String, List<CodeMethod>> byName = methods.stream()
            .collect(Collectors.groupingBy(CodeMethod::getSimpleName));

        for (Map.Entry<String, List<CodeMethod>> entry : byName.entrySet()) {
            String simpleName = entry.getKey();
            if (simpleName == null || simpleName.startsWith("<") || "init".equalsIgnoreCase(simpleName) || simpleName.isBlank()) {
                continue; // Constructors / initializers have distinct purposes and can never be divergent overrides
            }
            List<CodeMethod> group = entry.getValue();
            if (group.size() < 2) continue;

            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    CodeMethod m1 = group.get(i);
                    CodeMethod m2 = group.get(j);

                    if (isConstructor(m1) || isConstructor(m2)) {
                        continue;
                    }

                    String type1 = m1.getDeclaringTypeFqn();
                    String type2 = m2.getDeclaringTypeFqn();

                    // Only compare methods in the same class or related type hierarchies
                    if (!areTypesRelated(type1, type2, typeAncestors)) {
                        continue;
                    }

                    boolean isSameClass = Objects.equals(type1, type2);
                    boolean rtDiff = !Objects.equals(m1.getReturnType(), m2.getReturnType());
                    boolean pcDiff = m1.getParameters().size() != m2.getParameters().size();
                    if (rtDiff || (!isSameClass && pcDiff)) {
                        String relationDesc;
                        if (isSameClass) {
                            relationDesc = "within class " + extractSimpleClass(type1);
                        } else {
                            relationDesc = "across related types (" + extractSimpleClass(type1) + " / " + extractSimpleClass(type2) + ")";
                        }
                        out.add(report(m1.getFqn(), "METHOD",
                                       m2.getFqn(), "METHOD",
                                       "DIVERGENT_SIGNATURE",
                                       buildSignatureReason(m1, m2, rtDiff, pcDiff, relationDesc),
                                       1.0));
                    }
                }
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pass 2 – Similar names (Levenshtein ≤ 2 within the same class)
    // ─────────────────────────────────────────────────────────────────────────

    private List<InconsistencyReport> detectSimilarNames(List<CodeMethod> methods,
                                                         List<CodeField>  fields,
                                                         Map<String, Set<String>> typeAncestors) {
        List<InconsistencyReport> out = new ArrayList<>();

        // Group methods by declaring type so we only compare methods within the same class
        Map<String, List<CodeMethod>> methodsByType = methods.stream()
            .filter(m -> m.getDeclaringTypeFqn() != null)
            .collect(Collectors.groupingBy(CodeMethod::getDeclaringTypeFqn));

        for (List<CodeMethod> classMethods : methodsByType.values()) {
            if (classMethods.size() < 2) continue;
            for (int i = 0; i < classMethods.size(); i++) {
                for (int j = i + 1; j < classMethods.size(); j++) {
                    CodeMethod m1 = classMethods.get(i);
                    CodeMethod m2 = classMethods.get(j);
                    if (m1.getSimpleName().equals(m2.getSimpleName())) continue; // handled by Pass 1
                    int dist = levenshtein(m1.getSimpleName(), m2.getSimpleName());
                    if (dist <= 2) {
                        double sim = nameSimilarity(m1.getSimpleName(), m2.getSimpleName());
                        boolean bodyDiff = !Objects.equals(m1.getBodyHash(), m2.getBodyHash());
                        if (sim >= NAME_SIMILARITY_THRESHOLD && bodyDiff) {
                            out.add(report(m1.getFqn(), "METHOD", m2.getFqn(), "METHOD",
                                           "SIMILAR_NAME",
                                           "Method names in same class differ by " + dist + " edit(s); bodies diverge",
                                           sim));
                        }
                    }
                }
            }
        }

        // Group fields by declaring type so we only compare fields within the same class
        Map<String, List<CodeField>> fieldsByType = fields.stream()
            .filter(f -> f.getDeclaringTypeFqn() != null)
            .collect(Collectors.groupingBy(CodeField::getDeclaringTypeFqn));

        for (List<CodeField> classFields : fieldsByType.values()) {
            if (classFields.size() < 2) continue;
            for (int i = 0; i < classFields.size(); i++) {
                for (int j = i + 1; j < classFields.size(); j++) {
                    CodeField f1 = classFields.get(i);
                    CodeField f2 = classFields.get(j);
                    int dist = levenshtein(f1.getSimpleName(), f2.getSimpleName());
                    if (dist <= 2 && !Objects.equals(f1.getFieldType(), f2.getFieldType())) {
                        double sim = nameSimilarity(f1.getSimpleName(), f2.getSimpleName());
                        if (sim >= NAME_SIMILARITY_THRESHOLD) {
                            out.add(report(f1.getFqn(), "FIELD", f2.getFqn(), "FIELD",
                                           "SIMILAR_NAME",
                                           "Field names in same class similar (dist=" + dist + ") but types differ: "
                                           + f1.getFieldType() + " vs " + f2.getFieldType(),
                                           sim));
                        }
                    }
                }
            }
        }

        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pass 3 – Similar bodies (duplicate logic candidates within the same class)
    // ─────────────────────────────────────────────────────────────────────────

    private List<InconsistencyReport> detectSimilarBodies(List<CodeMethod> methods) {
        List<InconsistencyReport> out = new ArrayList<>();

        // Group by declaring type then by bodyHash
        Map<String, List<CodeMethod>> byType = methods.stream()
            .filter(m -> m.getBodyHash() != null && m.getDeclaringTypeFqn() != null)
            .collect(Collectors.groupingBy(CodeMethod::getDeclaringTypeFqn));

        for (List<CodeMethod> group : byType.values()) {
            Map<String, List<CodeMethod>> byHash = group.stream()
                .collect(Collectors.groupingBy(CodeMethod::getBodyHash));

            for (List<CodeMethod> sameHash : byHash.values()) {
                if (sameHash.size() < 2) continue;
                for (int i = 0; i < sameHash.size(); i++) {
                    for (int j = i + 1; j < sameHash.size(); j++) {
                        CodeMethod m1 = sameHash.get(i);
                        CodeMethod m2 = sameHash.get(j);
                        if (m1.getSimpleName().equals(m2.getSimpleName())) continue;
                        out.add(report(m1.getFqn(), "METHOD", m2.getFqn(), "METHOD",
                                       "SIMILAR_BODY",
                                       "Identical body hash in same class — possible duplication",
                                       0.95));
                    }
                }
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    private InconsistencyReport report(String fqn1, String kind1,
                                        String fqn2, String kind2,
                                        String type, String reason,
                                        double score) {
        InconsistencyReport r = new InconsistencyReport();
        r.setId(UUID.randomUUID().toString());
        r.setEntity1Fqn(fqn1);  r.setEntity1Kind(kind1);
        r.setEntity2Fqn(fqn2);  r.setEntity2Kind(kind2);
        r.setKind(type);
        r.setReason(reason);
        r.setSimilarityScore(score);
        return r;
    }

    private String buildSignatureReason(CodeMethod m1, CodeMethod m2,
                                         boolean rtDiff, boolean pcDiff,
                                         String context) {
        StringBuilder sb = new StringBuilder("Method '")
            .append(m1.getSimpleName()).append("' has divergent signature ").append(context).append(": ");
        if (rtDiff) sb.append("return type (")
            .append(m1.getReturnType()).append(" vs ").append(m2.getReturnType()).append(") ");
        if (pcDiff) sb.append("param count (")
            .append(m1.getParameters().size()).append(" vs ")
            .append(m2.getParameters().size()).append(")");
        return sb.toString().trim();
    }

    private static boolean isConstructor(CodeMethod m) {
        if (m == null) return false;
        String name = m.getSimpleName();
        if (name == null || name.startsWith("<") || "init".equalsIgnoreCase(name)) {
            return true;
        }
        if (m.getDeclaringTypeFqn() != null) {
            String declaringSimple = extractSimpleClass(m.getDeclaringTypeFqn());
            if (declaringSimple.equalsIgnoreCase(name) && (m.getReturnType() == null || m.getReturnType().isBlank())) {
                return true;
            }
        }
        return false;
    }

    private static String extractSimpleClass(String classFqn) {
        if (classFqn == null) return "";
        int dot = classFqn.lastIndexOf('.');
        return dot >= 0 ? classFqn.substring(dot + 1) : classFqn;
    }

    /** Classic Wagner-Fischer Levenshtein distance. */
    private int levenshtein(String a, String b) {
        int la = a.length(), lb = b.length();
        int[][] dp = new int[la + 1][lb + 1];
        for (int i = 0; i <= la; i++) dp[i][0] = i;
        for (int j = 0; j <= lb; j++) dp[0][j] = j;
        for (int i = 1; i <= la; i++)
            for (int j = 1; j <= lb; j++)
                dp[i][j] = (a.charAt(i-1) == b.charAt(j-1))
                    ? dp[i-1][j-1]
                    : 1 + Math.min(dp[i-1][j-1], Math.min(dp[i-1][j], dp[i][j-1]));
        return dp[la][lb];
    }

    /** Normalised similarity: 1 − (editDist / maxLen). */
    private double nameSimilarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - (double) levenshtein(a, b) / maxLen;
    }
}
