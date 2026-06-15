package com.codelens.core.model;

/** Free-text note attached to any entity (type, method, or field) by an analyst. */
public class AnalystNote {
    private String id;          // UUID PK
    private String entityFqn;   // FQN of the annotated entity
    private String content;     // markdown-compatible free text
    private long createdAt;     // epoch millis
    private long updatedAt;     // epoch millis

    // ── Getters & Setters ────────────────────────────────────────────────────
    public String getId()               { return id; }
    public void setId(String id)       { this.id = id; }
    public String getEntityFqn()        { return entityFqn; }
    public void setEntityFqn(String e) { this.entityFqn = e; }
    public String getContent()          { return content; }
    public void setContent(String c)   { this.content = c; }
    public long getCreatedAt()          { return createdAt; }
    public void setCreatedAt(long t)   { this.createdAt = t; }
    public long getUpdatedAt()          { return updatedAt; }
    public void setUpdatedAt(long t)   { this.updatedAt = t; }
}
