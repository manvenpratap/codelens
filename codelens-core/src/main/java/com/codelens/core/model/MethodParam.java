package com.codelens.core.model;

/** A single method parameter (type + name pair). */
public record MethodParam(String type, String name) {
    public String getType() { return type; }
    public String getName() { return name; }
}
