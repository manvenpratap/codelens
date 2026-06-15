package com.codelens.core.model;

/**
 * Snapshot of the current (or last completed) scan operation.
 * Polled by the UI every second while status == SCANNING.
 */
public class ScanProgress {
    public enum Status { IDLE, SCANNING, COMPLETE, ERROR }

    private Status status = Status.IDLE;
    private String sourcePath;
    private int totalFiles;
    private int processedFiles;
    private int typesFound;
    private int methodsFound;
    private int fieldsFound;
    private int relationshipsFound;
    private String message;
    private long startTime;
    private long endTime;
    private String errorDetail;

    public ScanProgress() {}
    public ScanProgress(Status status) { this.status = status; }

    /** Percentage complete (0–100). */
    public int getPercentage() {
        if (totalFiles == 0) return 0;
        return Math.min(100, (int) ((processedFiles * 100L) / totalFiles));
    }

    // ── Getters & Setters ────────────────────────────────────────────────────
    public Status getStatus()                   { return status; }
    public void setStatus(Status s)            { this.status = s; }
    public String getSourcePath()               { return sourcePath; }
    public void setSourcePath(String p)        { this.sourcePath = p; }
    public int getTotalFiles()                  { return totalFiles; }
    public void setTotalFiles(int n)           { this.totalFiles = n; }
    public int getProcessedFiles()              { return processedFiles; }
    public void setProcessedFiles(int n)       { this.processedFiles = n; }
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
}
