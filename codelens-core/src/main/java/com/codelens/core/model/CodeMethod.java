package com.codelens.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a method or constructor discovered during parsing.
 * FQN format: "com.example.Foo.bar(String,int)" — type-erased params, no spaces.
 */
public class CodeMethod {
    private String id;                          // == fqn; PK
    private String fqn;                         // full signature FQN
    private String simpleName;                  // method name only
    private String declaringTypeFqn;            // owning class/interface FQN
    private String returnType;                  // return type (unresolved)
    private List<MethodParam> parameters = new ArrayList<>();
    private String modifiers;                   // "public static" etc.
    private int startLine;
    private int endLine;
    private int cyclomaticComplexity;           // decision-point count + 1
    private String bodyHash;                    // 16-char SHA-256 prefix for similarity

    // ── Getters & Setters ────────────────────────────────────────────────────
    public String getId()                               { return id; }
    public void setId(String id)                       { this.id = id; }
    public String getFqn()                              { return fqn; }
    public void setFqn(String fqn)                     { this.fqn = fqn; }
    public String getSimpleName()                       { return simpleName; }
    public void setSimpleName(String s)                { this.simpleName = s; }
    public String getDeclaringTypeFqn()                 { return declaringTypeFqn; }
    public void setDeclaringTypeFqn(String d)          { this.declaringTypeFqn = d; }
    public String getReturnType()                       { return returnType; }
    public void setReturnType(String r)                { this.returnType = r; }
    public List<MethodParam> getParameters()           { return parameters; }
    public void setParameters(List<MethodParam> p)     { this.parameters = p; }
    public String getModifiers()                        { return modifiers; }
    public void setModifiers(String m)                 { this.modifiers = m; }
    public int getStartLine()                           { return startLine; }
    public void setStartLine(int n)                    { this.startLine = n; }
    public int getEndLine()                             { return endLine; }
    public void setEndLine(int n)                      { this.endLine = n; }
    public int getCyclomaticComplexity()               { return cyclomaticComplexity; }
    public void setCyclomaticComplexity(int c)         { this.cyclomaticComplexity = c; }
    public String getBodyHash()                         { return bodyHash; }
    public void setBodyHash(String h)                  { this.bodyHash = h; }
}
