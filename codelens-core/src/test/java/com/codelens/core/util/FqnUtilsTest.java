package com.codelens.core.util;

import java.util.Objects;

public class FqnUtilsTest {

    private static void assertEquals(String expected, String actual, String msg) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(msg + " (expected: \"" + expected + "\", got: \"" + actual + "\")");
        }
    }

    public void testExtractClassFqn() {
        assertEquals("com.tcs.bancs.AM.Account", FqnUtils.extractClassFqn("com.tcs.bancs.AM.Account.Get()"), "Method with parens");
        assertEquals("com.tcs.bancs.AM.Account", FqnUtils.extractClassFqn("com.tcs.bancs.AM.Account.Get(String,int)"), "Method with params");
        assertEquals("com.tcs.bancs.AM.Account", FqnUtils.extractClassFqn("com.tcs.bancs.AM.Account.field"), "Field without parens");
        assertEquals("", FqnUtils.extractClassFqn(""), "Empty string");
        assertEquals("", FqnUtils.extractClassFqn(null), "Null string");
        assertEquals("Account", FqnUtils.extractClassFqn("Account.Get()"), "Simple class with method");
    }

    public void testExtractClassSimple() {
        assertEquals("Account", FqnUtils.extractClassSimple("com.tcs.bancs.AM.Account"), "FQN class");
        assertEquals("Account", FqnUtils.extractClassSimple("Account"), "Already simple class");
        assertEquals("", FqnUtils.extractClassSimple(""), "Empty string");
        assertEquals("", FqnUtils.extractClassSimple(null), "Null string");
    }

    public void testExtractSimpleName() {
        assertEquals("Get", FqnUtils.extractSimpleName("com.tcs.bancs.AM.Account.Get()"), "Method with parens");
        assertEquals("Get", FqnUtils.extractSimpleName("com.tcs.bancs.AM.Account.Get(String)"), "Method with params");
        assertEquals("field", FqnUtils.extractSimpleName("com.tcs.bancs.AM.Account.field"), "Field name");
        assertEquals("", FqnUtils.extractSimpleName(""), "Empty string");
        assertEquals("", FqnUtils.extractSimpleName(null), "Null string");
    }

    public void testExtractPackageFromFqn() {
        assertEquals("com.tcs.bancs.AM", FqnUtils.extractPackageFromFqn("com.tcs.bancs.AM.Account"), "FQN class package");
        assertEquals("(default)", FqnUtils.extractPackageFromFqn("Account"), "No package");
        assertEquals("(default)", FqnUtils.extractPackageFromFqn(""), "Empty string");
        assertEquals("(default)", FqnUtils.extractPackageFromFqn(null), "Null string");
    }

    public void testExtractPackageSimple() {
        assertEquals("AM", FqnUtils.extractPackageSimple("com.tcs.bancs.AM"), "Sub-package simple");
        assertEquals("com", FqnUtils.extractPackageSimple("com"), "Single segment package");
        assertEquals("(default)", FqnUtils.extractPackageSimple(null), "Null string");
    }
}
