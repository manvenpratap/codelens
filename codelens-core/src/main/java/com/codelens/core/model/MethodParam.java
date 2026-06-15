package com.codelens.core.model;

/** A single method parameter (type + name pair). */
public class MethodParam {
    private String type;
    private String name;

    public MethodParam() {}
    public MethodParam(String type, String name) { this.type = type; this.name = name; }

    public String getType()         { return type; }
    public void setType(String t)   { this.type = t; }
    public String getName()         { return name; }
    public void setName(String n)   { this.name = n; }
}
