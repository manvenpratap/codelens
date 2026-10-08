package com.codelens.core.model;

/**
 * A directed edge between two entities.
 *
 * Supported kinds:
 *   CALLS        – method calls method
 *   READS_FIELD  – method reads field
 *   WRITES_FIELD – method writes field
 *   EXTENDS      – type extends type
 *   IMPLEMENTS   – type implements interface
 *
 * toEntityFqn may be prefixed with "~" to indicate an unresolved reference
 * (e.g. "~repository.save") that needs post-scan resolution by the analyzer.
 */
public class CodeRelationship {
    // Standard relationship kinds
    public static final String KIND_CALLS            = "CALLS";
    public static final String KIND_READS_FIELD      = "READS_FIELD";
    public static final String KIND_WRITES_FIELD     = "WRITES_FIELD";
    public static final String KIND_EXTENDS          = "EXTENDS";
    public static final String KIND_IMPLEMENTS       = "IMPLEMENTS";
    public static final String KIND_HANDLED_BY       = "HANDLED_BY";
    public static final String KIND_EXPOSES_ENDPOINT = "EXPOSES_ENDPOINT";
    public static final String KIND_ACCESSES_TABLE   = "ACCESSES_TABLE";
    public static final String KIND_READS_TABLE      = "READS_TABLE";
    public static final String KIND_WRITES_TABLE     = "WRITES_TABLE";
    public static final String KIND_MAPS_TO_TABLE    = "MAPS_TO_TABLE";
    public static final String KIND_PUBLISHES_EVENT  = "PUBLISHES_EVENT";
    public static final String KIND_LISTENS_EVENT    = "LISTENS_EVENT";

    private String id;              // UUID or deterministic hash
    private String fromEntityFqn;
    private String toEntityFqn;
    private String kind;            // e.g. CALLS, HANDLED_BY, ACCESSES_TABLE, PUBLISHES_EVENT
    private int sourceLine;         // line number in the source file

    public CodeRelationship() {}

    public CodeRelationship(String id, String fromEntityFqn, String toEntityFqn, String kind, int sourceLine) {
        this.id = id;
        this.fromEntityFqn = fromEntityFqn;
        this.toEntityFqn = toEntityFqn;
        this.kind = kind;
        this.sourceLine = sourceLine;
    }

    // ── Getters & Setters ────────────────────────────────────────────────────
    public String getId()                       { return id; }
    public void setId(String id)               { this.id = id; }
    public String getFromEntityFqn()            { return fromEntityFqn; }
    public void setFromEntityFqn(String f)     { this.fromEntityFqn = f; }
    public String getToEntityFqn()              { return toEntityFqn; }
    public void setToEntityFqn(String t)       { this.toEntityFqn = t; }
    public String getKind()                     { return kind; }
    public void setKind(String kind)           { this.kind = kind; }
    public int getSourceLine()                  { return sourceLine; }
    public void setSourceLine(int n)           { this.sourceLine = n; }
}
