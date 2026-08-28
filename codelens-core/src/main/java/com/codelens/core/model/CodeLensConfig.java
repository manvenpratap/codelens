package com.codelens.core.model;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/**
 * Portable configuration model for CodeLens.
 * Encapsulates server, storage, scanning, UI, physics, macro visualizer,
 * code review thresholds, POJO rules, and custom archetype rules.
 *
 * Supports serialization/deserialization to standard .conf (properties format with section headers).
 */
public class CodeLensConfig {

    // ── Server & Storage ──
    private int port = 7878;
    private String dataDir = "./codelens-data";
    private String defaultScanPath = "";
    private String excludePatterns = "target, build, .mvn, .git, .gradle, node_modules, bin, out";

    // ── UI & Appearance ──
    private String theme = "dark";
    private String packageMode = "auto";
    private String defaultTab = "graph";
    private String tabOrder = "[\"graph\",\"knowledge\",\"review\",\"git\",\"source\"]";

    // ── Graph Physics & Visuals ──
    private int nodeBaseRadius = 12;
    private int repulsion = 350;
    private int springLen = 120;
    private double damping = 0.85;
    private boolean showParticles = true;
    private boolean showMinimap = true;
    private boolean showLabels = true;
    private boolean showGrid = true;
    private boolean showHulls = true;
    private int defaultDepth = 3;
    private boolean autoFit = true;

    // ── Macro 3D & 2D Studio ──
    private String defaultMacroLevel = "city3d";
    private String defaultMacroGranularity = "arch";
    private double macroBrightness = 1.0;
    private boolean macroShowArcs = true;
    private boolean macroAutoRotate = false;
    private boolean macroShowWireframe = false;

    // ── Code Review Thresholds ──
    private int cyclomaticComplexityThreshold = 15;
    private int cognitiveComplexityThreshold = 15;
    private int methodLinesThreshold = 50;
    private int classLinesThreshold = 500;
    private int parameterCountThreshold = 6;

    // ── POJO Classification ──
    private boolean pojoIncludeStandardAccessors = true;
    private String pojoCustomPatterns = "get*, set*, is*, has*, with*";

    // ── Custom Archetype Rules (JSON string array) ──
    private String archetypeRulesJson = "[]";

    public CodeLensConfig() {}

    // ── Getters and Setters ──

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir != null ? dataDir : "./codelens-data"; }

    public String getDefaultScanPath() { return defaultScanPath; }
    public void setDefaultScanPath(String defaultScanPath) { this.defaultScanPath = defaultScanPath != null ? defaultScanPath : ""; }

    public String getExcludePatterns() { return excludePatterns; }
    public void setExcludePatterns(String excludePatterns) { this.excludePatterns = excludePatterns != null ? excludePatterns : ""; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme != null ? theme : "dark"; }

    public String getPackageMode() { return packageMode; }
    public void setPackageMode(String packageMode) { this.packageMode = packageMode != null ? packageMode : "auto"; }

    public String getDefaultTab() { return defaultTab; }
    public void setDefaultTab(String defaultTab) { this.defaultTab = defaultTab != null ? defaultTab : "graph"; }

    @com.fasterxml.jackson.annotation.JsonProperty("tabOrder")
    public String getTabOrder() { return tabOrder; }

    @com.fasterxml.jackson.annotation.JsonSetter("tabOrder")
    public void setTabOrderFromJson(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            this.tabOrder = "[\"graph\",\"knowledge\",\"review\",\"git\",\"source\"]";
        } else if (node.isArray()) {
            this.tabOrder = node.toString();
        } else if (node.isTextual()) {
            this.tabOrder = node.asText();
        } else {
            this.tabOrder = node.toString();
        }
    }

    public void setTabOrder(String tabOrder) { this.tabOrder = tabOrder != null ? tabOrder : "[\"graph\",\"knowledge\",\"review\",\"git\",\"source\"]"; }

    public int getNodeBaseRadius() { return nodeBaseRadius; }
    public void setNodeBaseRadius(int nodeBaseRadius) { this.nodeBaseRadius = nodeBaseRadius; }

    public int getRepulsion() { return repulsion; }
    public void setRepulsion(int repulsion) { this.repulsion = repulsion; }

    public int getSpringLen() { return springLen; }
    public void setSpringLen(int springLen) { this.springLen = springLen; }

    public double getDamping() { return damping; }
    public void setDamping(double damping) { this.damping = damping; }

    public boolean isShowParticles() { return showParticles; }
    public void setShowParticles(boolean showParticles) { this.showParticles = showParticles; }

    public boolean isShowMinimap() { return showMinimap; }
    public void setShowMinimap(boolean showMinimap) { this.showMinimap = showMinimap; }

    public boolean isShowLabels() { return showLabels; }
    public void setShowLabels(boolean showLabels) { this.showLabels = showLabels; }

    public boolean isShowGrid() { return showGrid; }
    public void setShowGrid(boolean showGrid) { this.showGrid = showGrid; }

    public boolean isShowHulls() { return showHulls; }
    public void setShowHulls(boolean showHulls) { this.showHulls = showHulls; }

    public int getDefaultDepth() { return defaultDepth; }
    public void setDefaultDepth(int defaultDepth) { this.defaultDepth = defaultDepth; }

    public boolean isAutoFit() { return autoFit; }
    public void setAutoFit(boolean autoFit) { this.autoFit = autoFit; }

    public String getDefaultMacroLevel() { return defaultMacroLevel; }
    public void setDefaultMacroLevel(String defaultMacroLevel) { this.defaultMacroLevel = defaultMacroLevel != null ? defaultMacroLevel : "city3d"; }

    public String getDefaultMacroGranularity() { return defaultMacroGranularity; }
    public void setDefaultMacroGranularity(String defaultMacroGranularity) { this.defaultMacroGranularity = defaultMacroGranularity != null ? defaultMacroGranularity : "arch"; }

    public double getMacroBrightness() { return macroBrightness; }
    public void setMacroBrightness(double macroBrightness) { this.macroBrightness = macroBrightness; }

    public boolean isMacroShowArcs() { return macroShowArcs; }
    public void setMacroShowArcs(boolean macroShowArcs) { this.macroShowArcs = macroShowArcs; }

    public boolean isMacroAutoRotate() { return macroAutoRotate; }
    public void setMacroAutoRotate(boolean macroAutoRotate) { this.macroAutoRotate = macroAutoRotate; }

    public boolean isMacroShowWireframe() { return macroShowWireframe; }
    public void setMacroShowWireframe(boolean macroShowWireframe) { this.macroShowWireframe = macroShowWireframe; }

    public int getCyclomaticComplexityThreshold() { return cyclomaticComplexityThreshold; }
    public void setCyclomaticComplexityThreshold(int cyclomaticComplexityThreshold) { this.cyclomaticComplexityThreshold = cyclomaticComplexityThreshold; }

    public int getCognitiveComplexityThreshold() { return cognitiveComplexityThreshold; }
    public void setCognitiveComplexityThreshold(int cognitiveComplexityThreshold) { this.cognitiveComplexityThreshold = cognitiveComplexityThreshold; }

    public int getMethodLinesThreshold() { return methodLinesThreshold; }
    public void setMethodLinesThreshold(int methodLinesThreshold) { this.methodLinesThreshold = methodLinesThreshold; }

    public int getClassLinesThreshold() { return classLinesThreshold; }
    public void setClassLinesThreshold(int classLinesThreshold) { this.classLinesThreshold = classLinesThreshold; }

    public int getParameterCountThreshold() { return parameterCountThreshold; }
    public void setParameterCountThreshold(int parameterCountThreshold) { this.parameterCountThreshold = parameterCountThreshold; }

    public boolean isPojoIncludeStandardAccessors() { return pojoIncludeStandardAccessors; }
    public void setPojoIncludeStandardAccessors(boolean pojoIncludeStandardAccessors) { this.pojoIncludeStandardAccessors = pojoIncludeStandardAccessors; }

    public String getPojoCustomPatterns() { return pojoCustomPatterns; }
    public void setPojoCustomPatterns(String pojoCustomPatterns) { this.pojoCustomPatterns = pojoCustomPatterns != null ? pojoCustomPatterns : ""; }

    @com.fasterxml.jackson.annotation.JsonProperty("archetypeRulesJson")
    public String getArchetypeRulesJson() { return archetypeRulesJson; }

    @com.fasterxml.jackson.annotation.JsonSetter("archetypeRulesJson")
    public void setArchetypeRulesJsonFromJson(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            this.archetypeRulesJson = "[]";
        } else if (node.isArray()) {
            this.archetypeRulesJson = node.toString();
        } else if (node.isTextual()) {
            this.archetypeRulesJson = node.asText();
        } else {
            this.archetypeRulesJson = node.toString();
        }
    }

    public void setArchetypeRulesJson(String archetypeRulesJson) { this.archetypeRulesJson = archetypeRulesJson != null ? archetypeRulesJson : "[]"; }

    // ── Serialization / Deserialization ──

    /**
     * Serializes this configuration to a human-readable .conf format.
     */
    public String toConfString() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ═══════════════════════════════════════════════════════════════════════════════\n");
        sb.append("# CodeLens Deployment Configuration (codelens.conf)\n");
        sb.append("# Generated: ").append(Instant.now().toString()).append("\n");
        sb.append("# Use this file to import and restore settings on new deployments.\n");
        sb.append("# ═══════════════════════════════════════════════════════════════════════════════\n\n");

        sb.append("# ── Server & Storage ──\n");
        sb.append("server.port=").append(port).append("\n");
        sb.append("storage.dataDir=").append(escapeVal(dataDir)).append("\n");
        sb.append("scan.defaultPath=").append(escapeVal(defaultScanPath)).append("\n");
        sb.append("scan.excludePatterns=").append(escapeVal(excludePatterns)).append("\n\n");

        sb.append("# ── UI & Appearance ──\n");
        sb.append("ui.theme=").append(escapeVal(theme)).append("\n");
        sb.append("ui.packageMode=").append(escapeVal(packageMode)).append("\n");
        sb.append("ui.defaultTab=").append(escapeVal(defaultTab)).append("\n");
        sb.append("ui.tabOrder=").append(escapeVal(tabOrder)).append("\n\n");

        sb.append("# ── Graph Physics & Visuals ──\n");
        sb.append("graph.nodeBaseRadius=").append(nodeBaseRadius).append("\n");
        sb.append("graph.repulsion=").append(repulsion).append("\n");
        sb.append("graph.springLen=").append(springLen).append("\n");
        sb.append("graph.damping=").append(damping).append("\n");
        sb.append("graph.showParticles=").append(showParticles).append("\n");
        sb.append("graph.showMinimap=").append(showMinimap).append("\n");
        sb.append("graph.showLabels=").append(showLabels).append("\n");
        sb.append("graph.showGrid=").append(showGrid).append("\n");
        sb.append("graph.showHulls=").append(showHulls).append("\n");
        sb.append("graph.defaultDepth=").append(defaultDepth).append("\n");
        sb.append("graph.autoFit=").append(autoFit).append("\n\n");

        sb.append("# ── Macro 3D & 2D Studio ──\n");
        sb.append("macro.defaultLevel=").append(escapeVal(defaultMacroLevel)).append("\n");
        sb.append("macro.defaultGranularity=").append(escapeVal(defaultMacroGranularity)).append("\n");
        sb.append("macro.brightness=").append(macroBrightness).append("\n");
        sb.append("macro.showArcs=").append(macroShowArcs).append("\n");
        sb.append("macro.autoRotate=").append(macroAutoRotate).append("\n");
        sb.append("macro.showWireframe=").append(macroShowWireframe).append("\n\n");

        sb.append("# ── Code Review Thresholds ──\n");
        sb.append("review.cyclomaticComplexityThreshold=").append(cyclomaticComplexityThreshold).append("\n");
        sb.append("review.cognitiveComplexityThreshold=").append(cognitiveComplexityThreshold).append("\n");
        sb.append("review.methodLinesThreshold=").append(methodLinesThreshold).append("\n");
        sb.append("review.classLinesThreshold=").append(classLinesThreshold).append("\n");
        sb.append("review.parameterCountThreshold=").append(parameterCountThreshold).append("\n\n");

        sb.append("# ── POJO Classification ──\n");
        sb.append("pojo.includeStandardAccessors=").append(pojoIncludeStandardAccessors).append("\n");
        sb.append("pojo.customPatterns=").append(escapeVal(pojoCustomPatterns)).append("\n\n");

        sb.append("# ── Custom Archetype Rules ──\n");
        sb.append("archetypes.rulesJson=").append(escapeVal(archetypeRulesJson)).append("\n");

        return sb.toString();
    }

    /**
     * Parses a .conf string and loads all properties into this instance.
     */
    public void loadFromConfString(String confContent) {
        if (confContent == null || confContent.isBlank()) return;

        Properties props = new Properties();
        try (StringReader reader = new StringReader(confContent)) {
            props.load(reader);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid .conf content format: " + e.getMessage(), e);
        }

        if (props.containsKey("server.port")) {
            this.port = parseInt(props.getProperty("server.port"), this.port);
        }
        if (props.containsKey("storage.dataDir")) {
            this.dataDir = props.getProperty("storage.dataDir");
        }
        if (props.containsKey("scan.defaultPath")) {
            this.defaultScanPath = props.getProperty("scan.defaultPath");
        }
        if (props.containsKey("scan.excludePatterns")) {
            this.excludePatterns = props.getProperty("scan.excludePatterns");
        }

        if (props.containsKey("ui.theme")) {
            this.theme = props.getProperty("ui.theme");
        }
        if (props.containsKey("ui.packageMode")) {
            this.packageMode = props.getProperty("ui.packageMode");
        }
        if (props.containsKey("ui.defaultTab")) {
            this.defaultTab = props.getProperty("ui.defaultTab");
        }
        if (props.containsKey("ui.tabOrder")) {
            this.tabOrder = props.getProperty("ui.tabOrder");
        }

        if (props.containsKey("graph.nodeBaseRadius")) {
            this.nodeBaseRadius = parseInt(props.getProperty("graph.nodeBaseRadius"), this.nodeBaseRadius);
        }
        if (props.containsKey("graph.repulsion")) {
            this.repulsion = parseInt(props.getProperty("graph.repulsion"), this.repulsion);
        }
        if (props.containsKey("graph.springLen")) {
            this.springLen = parseInt(props.getProperty("graph.springLen"), this.springLen);
        }
        if (props.containsKey("graph.damping")) {
            this.damping = parseDouble(props.getProperty("graph.damping"), this.damping);
        }
        if (props.containsKey("graph.showParticles")) {
            this.showParticles = Boolean.parseBoolean(props.getProperty("graph.showParticles"));
        }
        if (props.containsKey("graph.showMinimap")) {
            this.showMinimap = Boolean.parseBoolean(props.getProperty("graph.showMinimap"));
        }
        if (props.containsKey("graph.showLabels")) {
            this.showLabels = Boolean.parseBoolean(props.getProperty("graph.showLabels"));
        }
        if (props.containsKey("graph.showGrid")) {
            this.showGrid = Boolean.parseBoolean(props.getProperty("graph.showGrid"));
        }
        if (props.containsKey("graph.showHulls")) {
            this.showHulls = Boolean.parseBoolean(props.getProperty("graph.showHulls"));
        }
        if (props.containsKey("graph.defaultDepth")) {
            this.defaultDepth = parseInt(props.getProperty("graph.defaultDepth"), this.defaultDepth);
        }
        if (props.containsKey("graph.autoFit")) {
            this.autoFit = Boolean.parseBoolean(props.getProperty("graph.autoFit"));
        }

        if (props.containsKey("macro.defaultLevel")) {
            this.defaultMacroLevel = props.getProperty("macro.defaultLevel");
        }
        if (props.containsKey("macro.defaultGranularity")) {
            this.defaultMacroGranularity = props.getProperty("macro.defaultGranularity");
        }
        if (props.containsKey("macro.brightness")) {
            this.macroBrightness = parseDouble(props.getProperty("macro.brightness"), this.macroBrightness);
        }
        if (props.containsKey("macro.showArcs")) {
            this.macroShowArcs = Boolean.parseBoolean(props.getProperty("macro.showArcs"));
        }
        if (props.containsKey("macro.autoRotate")) {
            this.macroAutoRotate = Boolean.parseBoolean(props.getProperty("macro.autoRotate"));
        }
        if (props.containsKey("macro.showWireframe")) {
            this.macroShowWireframe = Boolean.parseBoolean(props.getProperty("macro.showWireframe"));
        }

        if (props.containsKey("review.cyclomaticComplexityThreshold")) {
            this.cyclomaticComplexityThreshold = parseInt(props.getProperty("review.cyclomaticComplexityThreshold"), this.cyclomaticComplexityThreshold);
        }
        if (props.containsKey("review.cognitiveComplexityThreshold")) {
            this.cognitiveComplexityThreshold = parseInt(props.getProperty("review.cognitiveComplexityThreshold"), this.cognitiveComplexityThreshold);
        }
        if (props.containsKey("review.methodLinesThreshold")) {
            this.methodLinesThreshold = parseInt(props.getProperty("review.methodLinesThreshold"), this.methodLinesThreshold);
        }
        if (props.containsKey("review.classLinesThreshold")) {
            this.classLinesThreshold = parseInt(props.getProperty("review.classLinesThreshold"), this.classLinesThreshold);
        }
        if (props.containsKey("review.parameterCountThreshold")) {
            this.parameterCountThreshold = parseInt(props.getProperty("review.parameterCountThreshold"), this.parameterCountThreshold);
        }

        if (props.containsKey("pojo.includeStandardAccessors")) {
            this.pojoIncludeStandardAccessors = Boolean.parseBoolean(props.getProperty("pojo.includeStandardAccessors"));
        }
        if (props.containsKey("pojo.customPatterns")) {
            this.pojoCustomPatterns = props.getProperty("pojo.customPatterns");
        }

        if (props.containsKey("archetypes.rulesJson")) {
            this.archetypeRulesJson = props.getProperty("archetypes.rulesJson");
        }
    }

    public static CodeLensConfig fromConfString(String conf) {
        CodeLensConfig config = new CodeLensConfig();
        config.loadFromConfString(conf);
        return config;
    }

    public static CodeLensConfig loadFromFile(File file) throws IOException {
        if (!file.exists()) return new CodeLensConfig();
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return fromConfString(content);
    }

    public void saveToFile(File file) throws IOException {
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        Files.writeString(file.toPath(), toConfString(), StandardCharsets.UTF_8);
    }

    private static String escapeVal(String val) {
        if (val == null) return "";
        return val.replace("\\", "\\\\");
    }

    private static int parseInt(String val, int def) {
        try {
            return Integer.parseInt(val.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static double parseDouble(String val, double def) {
        try {
            return Double.parseDouble(val.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
