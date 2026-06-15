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
 *      different classes but with different return types or param counts.
 *      (Signals: inconsistent protocol across collaborating types.)
 *
 *   2. SIMILAR_NAME — pairs of methods/fields whose names have Levenshtein
 *      distance ≤ 2 but whose types or bodies diverge.
 *      (Signals: copy-paste variations, naming drift.)
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
     * Run all detection passes and return a flat list of reports.
     *
     * @param methods all methods discovered in the scan
     * @param fields  all fields discovered in the scan
     */
    public List<InconsistencyReport> detect(List<CodeMethod> methods,
                                            List<CodeField>  fields) {
        List<InconsistencyReport> reports = new ArrayList<>();
        reports.addAll(detectDivergentSignatures(methods));
        reports.addAll(detectSimilarNames(methods, fields));
        reports.addAll(detectSimilarBodies(methods));
        log.info("Inconsistency detection: {} issues found", reports.size());
        return reports;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pass 1 – Divergent signatures
    // ─────────────────────────────────────────────────────────────────────────

    private List<InconsistencyReport> detectDivergentSignatures(List<CodeMethod> methods) {
        List<InconsistencyReport> out = new ArrayList<>();

        // Group methods by simple name (across all types)
        Map<String, List<CodeMethod>> byName = methods.stream()
            .collect(Collectors.groupingBy(CodeMethod::getSimpleName));

        for (Map.Entry<String, List<CodeMethod>> entry : byName.entrySet()) {
            List<CodeMethod> group = entry.getValue();
            if (group.size() < 2) continue;

            // Collect distinct return types and param counts
            Set<String> returnTypes  = group.stream()
                .map(CodeMethod::getReturnType).collect(Collectors.toSet());
            Set<Integer> paramCounts = group.stream()
                .map(m -> m.getParameters().size()).collect(Collectors.toSet());

            if (returnTypes.size() > 1 || paramCounts.size() > 1) {
                // Report all pairs that differ
                for (int i = 0; i < group.size(); i++) {
                    for (int j = i + 1; j < group.size(); j++) {
                        CodeMethod m1 = group.get(i);
                        CodeMethod m2 = group.get(j);
                        boolean rtDiff = !Objects.equals(m1.getReturnType(), m2.getReturnType());
                        boolean pcDiff = m1.getParameters().size() != m2.getParameters().size();
                        if (rtDiff || pcDiff) {
                            out.add(report(m1.getFqn(), "METHOD",
                                           m2.getFqn(), "METHOD",
                                           "DIVERGENT_SIGNATURE",
                                           buildSignatureReason(m1, m2, rtDiff, pcDiff),
                                           1.0));
                        }
                    }
                }
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pass 2 – Similar names (Levenshtein ≤ 2, structural divergence)
    // ─────────────────────────────────────────────────────────────────────────

    private List<InconsistencyReport> detectSimilarNames(List<CodeMethod> methods,
                                                          List<CodeField>  fields) {
        List<InconsistencyReport> out = new ArrayList<>();

        // Method pairs
        for (int i = 0; i < methods.size(); i++) {
            for (int j = i + 1; j < methods.size(); j++) {
                CodeMethod m1 = methods.get(i);
                CodeMethod m2 = methods.get(j);
                if (m1.getSimpleName().equals(m2.getSimpleName())) continue; // handled by Pass 1
                int dist = levenshtein(m1.getSimpleName(), m2.getSimpleName());
                if (dist <= 2) {
                    double sim = nameSimilarity(m1.getSimpleName(), m2.getSimpleName());
                    boolean bodyDiff = !Objects.equals(m1.getBodyHash(), m2.getBodyHash());
                    if (sim >= NAME_SIMILARITY_THRESHOLD && bodyDiff) {
                        out.add(report(m1.getFqn(), "METHOD", m2.getFqn(), "METHOD",
                                       "SIMILAR_NAME",
                                       "Names differ by " + dist + " edit(s); bodies diverge",
                                       sim));
                    }
                }
            }
        }

        // Field pairs — same type mismatch
        for (int i = 0; i < fields.size(); i++) {
            for (int j = i + 1; j < fields.size(); j++) {
                CodeField f1 = fields.get(i);
                CodeField f2 = fields.get(j);
                int dist = levenshtein(f1.getSimpleName(), f2.getSimpleName());
                if (dist <= 2 && !Objects.equals(f1.getFieldType(), f2.getFieldType())) {
                    double sim = nameSimilarity(f1.getSimpleName(), f2.getSimpleName());
                    if (sim >= NAME_SIMILARITY_THRESHOLD) {
                        out.add(report(f1.getFqn(), "FIELD", f2.getFqn(), "FIELD",
                                       "SIMILAR_NAME",
                                       "Field names similar (dist=" + dist + ") but types differ: "
                                       + f1.getFieldType() + " vs " + f2.getFieldType(),
                                       sim));
                    }
                }
            }
        }

        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pass 3 – Similar bodies (duplicate logic candidates)
    // ─────────────────────────────────────────────────────────────────────────

    private List<InconsistencyReport> detectSimilarBodies(List<CodeMethod> methods) {
        List<InconsistencyReport> out = new ArrayList<>();

        // Group by declaring type then by bodyHash
        Map<String, List<CodeMethod>> byType = methods.stream()
            .filter(m -> m.getBodyHash() != null)
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
                                         boolean rtDiff, boolean pcDiff) {
        StringBuilder sb = new StringBuilder("Method '")
            .append(m1.getSimpleName()).append("' has divergent signature: ");
        if (rtDiff) sb.append("return type (")
            .append(m1.getReturnType()).append(" vs ").append(m2.getReturnType()).append(") ");
        if (pcDiff) sb.append("param count (")
            .append(m1.getParameters().size()).append(" vs ")
            .append(m2.getParameters().size()).append(")");
        return sb.toString().trim();
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
