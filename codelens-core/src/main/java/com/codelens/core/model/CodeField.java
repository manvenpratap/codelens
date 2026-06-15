package com.codelens.core.model;

/**
 * Represents a field declaration on a Java class or interface.
 * FQN format: "com.example.Foo.myField"
 */
public class CodeField {
    private String id;              // == fqn; PK
    private String fqn;             // full field FQN
    private String simpleName;      // field name only
    private String declaringTypeFqn;
    private String fieldType;       // declared type (unresolved)
    private String modifiers;       // "private final" etc.
    private String initializer;     // source text of initializer if present
    private int startLine;

    // ── Getters & Setters ────────────────────────────────────────────────────
    public String getId()                       { return id; }
    public void setId(String id)               { this.id = id; }
    public String getFqn()                      { return fqn; }
    public void setFqn(String fqn)             { this.fqn = fqn; }
    public String getSimpleName()               { return simpleName; }
    public void setSimpleName(String s)        { this.simpleName = s; }
    public String getDeclaringTypeFqn()         { return declaringTypeFqn; }
    public void setDeclaringTypeFqn(String d)  { this.declaringTypeFqn = d; }
    public String getFieldType()                { return fieldType; }
    public void setFieldType(String t)         { this.fieldType = t; }
    public String getModifiers()                { return modifiers; }
    public void setModifiers(String m)         { this.modifiers = m; }
    public String getInitializer()              { return initializer; }
    public void setInitializer(String i)       { this.initializer = i; }
    public int getStartLine()                   { return startLine; }
    public void setStartLine(int n)            { this.startLine = n; }
}
