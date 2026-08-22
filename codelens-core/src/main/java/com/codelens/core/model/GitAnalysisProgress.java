package com.codelens.core.model;

/**
 * Snapshot of the current (or last completed) background Git analysis operation.
 * Polled by the UI while status == RUNNING.
 */
public class GitAnalysisProgress {
    public enum Status { IDLE, RUNNING, COMPLETE, ERROR }

    private Status status = Status.IDLE;
    private String repoPath;
    private String branch;
    private int totalFiles;
    private int processedFiles;
    private String currentFile;
    private int entitiesAnnotated;
    private String message;
    private long startTime;
    private long endTime;
    private String errorDetail;

    public GitAnalysisProgress() {}

    public GitAnalysisProgress(Status status) {
        this.status = status;
    }

    /** Percentage complete (0–100). */
    public int getPercentage() {
        if (totalFiles == 0) return 0;
        return Math.min(100, (int) ((processedFiles * 100L) / totalFiles));
    }

    // ── Getters & Setters ────────────────────────────────────────────────────
    public Status getStatus()                       { return status; }
    public void setStatus(Status status)           { this.status = status; }
    public String getRepoPath()                     { return repoPath; }
    public void setRepoPath(String repoPath)       { this.repoPath = repoPath; }
    public String getBranch()                       { return branch; }
    public void setBranch(String branch)           { this.branch = branch; }
    public int getTotalFiles()                      { return totalFiles; }
    public void setTotalFiles(int totalFiles)       { this.totalFiles = totalFiles; }
    public int getProcessedFiles()                  { return processedFiles; }
    public void setProcessedFiles(int p)           { this.processedFiles = p; }
    public String getCurrentFile()                  { return currentFile; }
    public void setCurrentFile(String currentFile) { this.currentFile = currentFile; }
    public int getEntitiesAnnotated()               { return entitiesAnnotated; }
    public void setEntitiesAnnotated(int e)        { this.entitiesAnnotated = e; }
    public String getMessage()                      { return message; }
    public void setMessage(String message)         { this.message = message; }
    public long getStartTime()                      { return startTime; }
    public void setStartTime(long startTime)       { this.startTime = startTime; }
    public long getEndTime()                        { return endTime; }
    public void setEndTime(long endTime)           { this.endTime = endTime; }
    public String getErrorDetail()                  { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
}
