package com.codelens.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a top-level Java type: class, interface, enum, or annotation.
 * Avoids clashing with java.lang.Class or java.reflect.Type.
 */
public class CodeType {
    private String id;              // == fqn; PK
    private String fqn;             // e.g. "com.example.trading.OrderService"
    private String simpleName;      // e.g. "OrderService"
    private String packageFqn;      // parent package
    private String kind;            // CLASS | INTERFACE | ENUM | ANNOTATION
    private String modifiers;       // "public abstract" etc.
    private String superClass;      // simple or unresolved FQN of parent class
    private List<String> interfaces = new ArrayList<>(); // implemented interfaces
    private String sourceFile;      // absolute path to .java file
    private int startLine;
    private int endLine;
    private int lineCount;
    private int fieldCount;
    private int methodCount;

    // ── Getters & Setters ────────────────────────────────────────────────────
    public String getId()                           { return id; }
    public void setId(String id)                   { this.id = id; }
    public String getFqn()                          { return fqn; }
    public void setFqn(String fqn)                 { this.fqn = fqn; }
    public String getSimpleName()                   { return simpleName; }
    public void setSimpleName(String s)            { this.simpleName = s; }
    public String getPackageFqn()                   { return packageFqn; }
    public void setPackageFqn(String p)            { this.packageFqn = p; }
    public String getKind()                         { return kind; }
    public void setKind(String kind)               { this.kind = kind; }
    public String getModifiers()                    { return modifiers; }
    public void setModifiers(String m)             { this.modifiers = m; }
    public String getSuperClass()                   { return superClass; }
    public void setSuperClass(String s)            { this.superClass = s; }
    public List<String> getInterfaces()            { return interfaces; }
    public void setInterfaces(List<String> i)      { this.interfaces = i; }
    public String getSourceFile()                   { return sourceFile; }
    public void setSourceFile(String s)            { this.sourceFile = s; }
    public int getStartLine()                       { return startLine; }
    public void setStartLine(int n)                { this.startLine = n; }
    public int getEndLine()                         { return endLine; }
    public void setEndLine(int n)                  { this.endLine = n; }
    public int getLineCount()                       { return lineCount; }
    public void setLineCount(int n)                { this.lineCount = n; }
    public int getFieldCount()                      { return fieldCount; }
    public void setFieldCount(int n)               { this.fieldCount = n; }
    public int getMethodCount()                     { return methodCount; }
    public void setMethodCount(int n)              { this.methodCount = n; }
}
