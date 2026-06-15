package com.codelens.core.model;

/**
 * A detected structural inconsistency between two related entities.
 *
 * Detection strategies (kind field):
 *   DIVERGENT_SIGNATURE  – same name, different return type or param count
 *   SIMILAR_NAME         – Levenshtein ≤ 2, different structure
 *   SIMILAR_BODY         – same params, different body hash (divergent logic)
 */
public class InconsistencyReport {
    private String id;
    private String entity1Fqn;
    private String entity1Kind;     // METHOD | FIELD
    private String entity2Fqn;
    private String entity2Kind;
    private String reason;          // human-readable explanation
    private double similarityScore; // 0–1, higher = more similar names
    private String kind;            // see javadoc above

    // ── Getters & Setters ────────────────────────────────────────────────────
    public String getId()                           { return id; }
    public void setId(String id)                   { this.id = id; }
    public String getEntity1Fqn()                   { return entity1Fqn; }
    public void setEntity1Fqn(String s)            { this.entity1Fqn = s; }
    public String getEntity1Kind()                  { return entity1Kind; }
    public void setEntity1Kind(String s)           { this.entity1Kind = s; }
    public String getEntity2Fqn()                   { return entity2Fqn; }
    public void setEntity2Fqn(String s)            { this.entity2Fqn = s; }
    public String getEntity2Kind()                  { return entity2Kind; }
    public void setEntity2Kind(String s)           { this.entity2Kind = s; }
    public String getReason()                       { return reason; }
    public void setReason(String r)                { this.reason = r; }
    public double getSimilarityScore()              { return similarityScore; }
    public void setSimilarityScore(double d)       { this.similarityScore = d; }
    public String getKind()                         { return kind; }
    public void setKind(String kind)               { this.kind = kind; }
}
