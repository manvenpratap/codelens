package com.codelens.core.model;

/**
 * Represents a Java package discovered during scanning.
 * id == fqn; kept separate for schema clarity.
 */
public class CodePackage {
    private String id;          // fully-qualified package name, used as PK
    private String fqn;         // e.g. "com.example.trading"
    private String name;        // leaf segment, e.g. "trading"
    private String parentFqn;   // parent package fqn, null for root
    private int fileCount;      // .java files directly in this package
    private int typeCount;      // types (classes/interfaces/enums) found

    public CodePackage() {}
    public CodePackage(String fqn) {
        this.id = fqn;
        this.fqn = fqn;
        int dot = fqn.lastIndexOf('.');
        this.name = (dot >= 0) ? fqn.substring(dot + 1) : fqn;
        this.parentFqn = (dot >= 0) ? fqn.substring(0, dot) : null;
    }

    // ── Getters & Setters ────────────────────────────────────────────────────
    public String getId()           { return id; }
    public void setId(String id)   { this.id = id; }
    public String getFqn()                  { return fqn; }
    public void setFqn(String fqn)          { this.fqn = fqn; }
    public String getName()                 { return name; }
    public void setName(String name)        { this.name = name; }
    public String getParentFqn()            { return parentFqn; }
    public void setParentFqn(String p)      { this.parentFqn = p; }
    public int getFileCount()               { return fileCount; }
    public void setFileCount(int n)         { this.fileCount = n; }
    public int getTypeCount()               { return typeCount; }
    public void setTypeCount(int n)         { this.typeCount = n; }
}
