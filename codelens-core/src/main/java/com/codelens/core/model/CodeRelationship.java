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
    private String id;              // UUID
    private String fromEntityFqn;
    private String toEntityFqn;
    private String kind;            // CALLS | READS_FIELD | WRITES_FIELD | EXTENDS | IMPLEMENTS
    private int sourceLine;         // line number in the source file

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
