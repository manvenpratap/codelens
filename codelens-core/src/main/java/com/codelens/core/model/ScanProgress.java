package com.codelens.core.model;

/**
 * Snapshot of the current (or last completed) scan operation.
 * Polled by the UI every second while status == SCANNING.
 */
public class ScanProgress {
    public enum Status { IDLE, SCANNING, COMPLETE, ERROR }

    private Status status = Status.IDLE;
    private String sourcePath;
    private String currentPhase;
    private String currentDetail;
    private int totalFiles;
    private int processedFiles;
    private int parsedFiles;
    private int errorFiles;
    private int typesFound;
    private int methodsFound;
    private int fieldsFound;
    private int relationshipsFound;
    private String message;
    private long startTime;
    private long endTime;
    private String errorDetail;
    private int percentage = -1;
    private String activeStage = "IDLE";
    private int stageCurrent = 0;
    private int stageTotal = 0;
    private String stageItem;
    private String metric1Label;
    private String metric1Value;
    private String metric2Label;
    private String metric2Value;
    private String metric3Label;
    private String metric3Value;
    private String metric4Label;
    private String metric4Value;

    public ScanProgress() {}
    public ScanProgress(Status status) { this.status = status; }

    /** Percentage complete (0–100). Returns explicitly assigned percentage if non-negative, else file-ratio. */
    public int getPercentage() {
        if (percentage >= 0) return Math.min(100, Math.max(0, percentage));
        if (totalFiles == 0) return 0;
        return Math.min(100, (int) ((processedFiles * 100L) / totalFiles));
    }

    public void setPercentage(int p) {
        this.percentage = p;
    }

    /** Pipeline stage: IDLE, PREPARE, PARSE, INDEX, GRAPH, LAYOUT, COMPLETE */
    public String getActiveStage() {
        return activeStage != null ? activeStage : "IDLE";
    }

    public void setActiveStage(String stage) {
        this.activeStage = stage;
    }

    /** Number of files remaining to be processed. */
    public int getRemainingFiles() {
        return Math.max(0, totalFiles - processedFiles);
    }

    /** Elapsed / total duration in milliseconds. */
    public long getDurationMs() {
        if (startTime <= 0) return 0;
        long end = (endTime > 0) ? endTime : System.currentTimeMillis();
        return Math.max(0, end - startTime);
    }

    /** Estimated remaining time in milliseconds based on current percentage and elapsed time. */
    public long getEstimatedRemainingMs() {
        if (status != Status.SCANNING || startTime <= 0) return 0;
        int pct = getPercentage();
        if (pct <= 2 || pct >= 100) return 0;
        long elapsed = getDurationMs();
        if (elapsed < 1000) return 0;
        long totalEstimate = (long) ((elapsed * 100.0) / pct);
        return Math.max(0, totalEstimate - elapsed);
    }

    // ── Getters & Setters ────────────────────────────────────────────────────
    public Status getStatus()                   { return status; }
    public void setStatus(Status s)            { this.status = s; }
    public String getSourcePath()               { return sourcePath; }
    public void setSourcePath(String p)        { this.sourcePath = p; }
    public String getCurrentPhase()             { return currentPhase; }
    public void setCurrentPhase(String phase)  { this.currentPhase = phase; }
    public String getCurrentDetail()            { return currentDetail; }
    public void setCurrentDetail(String detail){ this.currentDetail = detail; }
    public int getTotalFiles()                  { return totalFiles; }
    public void setTotalFiles(int n)           { this.totalFiles = n; }
    public int getProcessedFiles()              { return processedFiles; }
    public void setProcessedFiles(int n)       { this.processedFiles = n; }
    public int getParsedFiles()                 { return parsedFiles; }
    public void setParsedFiles(int n)          { this.parsedFiles = n; }
    public int getErrorFiles()                  { return errorFiles; }
    public void setErrorFiles(int n)           { this.errorFiles = n; }
    public int getTypesFound()                  { return typesFound; }
    public void setTypesFound(int n)           { this.typesFound = n; }
    public int getMethodsFound()                { return methodsFound; }
    public void setMethodsFound(int n)         { this.methodsFound = n; }
    public int getFieldsFound()                 { return fieldsFound; }
    public void setFieldsFound(int n)          { this.fieldsFound = n; }
    public int getRelationshipsFound()          { return relationshipsFound; }
    public void setRelationshipsFound(int n)   { this.relationshipsFound = n; }
    public String getMessage()                  { return message; }
    public void setMessage(String m)           { this.message = m; }
    public long getStartTime()                  { return startTime; }
    public void setStartTime(long t)           { this.startTime = t; }
    public long getEndTime()                    { return endTime; }
    public void setEndTime(long t)             { this.endTime = t; }
    public String getErrorDetail()              { return errorDetail; }
    public void setErrorDetail(String e)       { this.errorDetail = e; }

    public int getStageCurrent()                { return stageCurrent; }
    public void setStageCurrent(int c)          { this.stageCurrent = c; }
    public int getStageTotal()                  { return stageTotal; }
    public void setStageTotal(int t)            { this.stageTotal = t; }
    public String getStageItem()                { return stageItem; }
    public void setStageItem(String item)       { this.stageItem = item; }

    public void setSubProgress(int current, int total, String item) {
        this.stageCurrent = current;
        this.stageTotal = total;
        this.stageItem = item;
    }

    public String getMetric1Label()             { return metric1Label; }
    public void setMetric1Label(String l)       { this.metric1Label = l; }
    public String getMetric1Value()             { return metric1Value; }
    public void setMetric1Value(String v)       { this.metric1Value = v; }

    public String getMetric2Label()             { return metric2Label; }
    public void setMetric2Label(String l)       { this.metric2Label = l; }
    public String getMetric2Value()             { return metric2Value; }
    public void setMetric2Value(String v)       { this.metric2Value = v; }

    public String getMetric3Label()             { return metric3Label; }
    public void setMetric3Label(String l)       { this.metric3Label = l; }
    public String getMetric3Value()             { return metric3Value; }
    public void setMetric3Value(String v)       { this.metric3Value = v; }

    public String getMetric4Label()             { return metric4Label; }
    public void setMetric4Label(String l)       { this.metric4Label = l; }
    public String getMetric4Value()             { return metric4Value; }
    public void setMetric4Value(String v)       { this.metric4Value = v; }

    public void setDynamicMetrics(String l1, String v1, String l2, String v2, String l3, String v3, String l4, String v4) {
        this.metric1Label = l1;
        this.metric1Value = v1;
        this.metric2Label = l2;
        this.metric2Value = v2;
        this.metric3Label = l3;
        this.metric3Value = v3;
        this.metric4Label = l4;
        this.metric4Value = v4;
    }
}

