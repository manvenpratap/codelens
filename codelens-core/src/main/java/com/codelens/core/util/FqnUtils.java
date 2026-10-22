package com.codelens.core.util;

/**
 * Standard utility methods for decomposing and parsing Java Fully Qualified Names (FQNs)
 * of classes, packages, and methods.
 */
public final class FqnUtils {

    private FqnUtils() {}

    /**
     * Extracts the class FQN from a method or field FQN.
     * e.g. "com.tcs.bancs.AM.Account.Get()" -> "com.tcs.bancs.AM.Account"
     * e.g. "com.foo.Bar.field" -> "com.foo.Bar"
     */
    public static String extractClassFqn(String methodFqn) {
        if (methodFqn == null) return "";
        int paren = methodFqn.indexOf('(');
        String base = (paren >= 0) ? methodFqn.substring(0, paren) : methodFqn;
        int dot = base.lastIndexOf('.');
        return (dot >= 0) ? base.substring(0, dot) : base;
    }

    /**
     * Extracts the simple class name from a class FQN.
     * e.g. "com.tcs.bancs.AM.Account" -> "Account"
     */
    public static String extractClassSimple(String classFqn) {
        if (classFqn == null) return "";
        int dot = classFqn.lastIndexOf('.');
        return (dot >= 0) ? classFqn.substring(dot + 1) : classFqn;
    }

    /**
     * Extracts the simple name of a member or method (excluding parameter signature).
     * e.g. "com.tcs.bancs.AM.Account.Get(String)" -> "Get"
     */
    public static String extractSimpleName(String fqn) {
        if (fqn == null) return "";
        int paren = fqn.indexOf('(');
        String base = (paren >= 0) ? fqn.substring(0, paren) : fqn;
        int dot = base.lastIndexOf('.');
        return (dot >= 0) ? base.substring(dot + 1) : base;
    }

    /**
     * Extracts the package FQN from a class or method FQN.
     * e.g. "com.tcs.bancs.AM.Account" -> "com.tcs.bancs.AM"
     * Returns "(default)" if no package exists.
     */
    public static String extractPackageFromFqn(String classFqn) {
        if (classFqn == null) return "(default)";
        int dot = classFqn.lastIndexOf('.');
        return (dot >= 0) ? classFqn.substring(0, dot) : "(default)";
    }

    /**
     * Extracts the innermost package segment from a package FQN.
     * e.g. "com.tcs.bancs.AM" -> "AM"
     */
    public static String extractPackageSimple(String pkgFqn) {
        if (pkgFqn == null) return "(default)";
        int dot = pkgFqn.lastIndexOf('.');
        return (dot >= 0) ? pkgFqn.substring(dot + 1) : pkgFqn;
    }
}
