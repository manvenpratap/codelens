package com.codelens.core.model;

/**
 * A single finding produced by the on-demand Code Review engine.
 *
 * Categories:
 *   CORRECTNESS       — logic defects, null dereference, contract violations
 *   EXCEPTION_SAFETY  — swallowed exceptions, resource leaks, broad catch
 *   THREAD_SAFETY     — unsynchronized state, DCL, non-thread-safe types
 *   CODE_SMELL        — complexity, god methods/classes, magic numbers
 *   API_CONTRACT      — empty bodies, inconsistent returns, encapsulation breaks
 *   IMPACT            — high fan-in/out, field mutation blast radius
 *
 * Severities:
 *   CRITICAL — almost certainly a bug or will cause a bug under specific inputs
 *   WARNING  — likely problematic, should be addressed before shipping
 *   INFO     — code quality improvement opportunity
 */
public class ReviewFinding {
    private String id;              // UUID
    private String category;        // see javadoc above
    private String severity;        // CRITICAL | WARNING | INFO
    private String checkName;       // e.g. "NULL_DEREFERENCE_RISK"
    private String entityFqn;       // method or class FQN where the issue lives
    private String entityKind;      // METHOD | TYPE | FIELD
    private String message;         // Human-readable explanation
    private String suggestion;      // Actionable fix recommendation
    private int    line;            // Source line number (0 if unknown)
    private String sourceSnippet;   // 3-5 lines of surrounding code context

    // ── Getters & Setters ────────────────────────────────────────────────────
    public String getId()                           { return id; }
    public void setId(String id)                   { this.id = id; }
    public String getCategory()                     { return category; }
    public void setCategory(String category)       { this.category = category; }
    public String getSeverity()                     { return severity; }
    public void setSeverity(String severity)       { this.severity = severity; }
    public String getCheckName()                    { return checkName; }
    public void setCheckName(String checkName)     { this.checkName = checkName; }
    public String getEntityFqn()                    { return entityFqn; }
    public void setEntityFqn(String entityFqn)     { this.entityFqn = entityFqn; }
    public String getEntityKind()                   { return entityKind; }
    public void setEntityKind(String entityKind)   { this.entityKind = entityKind; }
    public String getMessage()                      { return message; }
    public void setMessage(String message)         { this.message = message; }
    public String getSuggestion()                   { return suggestion; }
    public void setSuggestion(String suggestion)   { this.suggestion = suggestion; }
    public int getLine()                            { return line; }
    public void setLine(int line)                  { this.line = line; }
    public String getSourceSnippet()                { return sourceSnippet; }
    public void setSourceSnippet(String snippet)   { this.sourceSnippet = snippet; }
}
