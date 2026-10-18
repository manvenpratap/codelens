package com.codelens.api;

import com.codelens.core.model.CodeLensConfig;

public class CodeLensConfigTest {

    private void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " - Expected: " + expected + ", Actual: " + actual);
        }
    }

    public void testDefaultMacroLevelIsGraph2D() {
        CodeLensConfig config = new CodeLensConfig();
        assertEquals("graph2d", config.getDefaultMacroLevel(), "Default studio macro level must be graph2d (2D graph)");

        config.setDefaultMacroLevel(null);
        assertEquals("graph2d", config.getDefaultMacroLevel(), "Null fallback should default to graph2d");

        String conf = config.toConfString();
        if (!conf.contains("macro.defaultLevel=graph2d")) {
            throw new AssertionError("toConfString() must contain macro.defaultLevel=graph2d");
        }

        CodeLensConfig parsed = CodeLensConfig.fromConfString(conf);
        assertEquals("graph2d", parsed.getDefaultMacroLevel(), "Parsed config should have graph2d as default macro level");
    }
}
