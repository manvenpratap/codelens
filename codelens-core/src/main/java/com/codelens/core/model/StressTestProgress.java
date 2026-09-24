package com.codelens.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot of the active (or last completed) Scale & Stress Testing benchmark.
 * Tracks live progress across entity ingestion, relationship streaming, index rebuilds,
 * database compaction, and query performance.
 */
public class StressTestProgress {

    public enum Status { IDLE, RUNNING, COMPLETE, ERROR, CANCELLED }

    private Status status = Status.IDLE;
    private String activeStage = "IDLE";
    private String currentPhase = "Idle";
    private String currentDetail = "";
    private int percentage = 0;

    private int targetClasses = 30_000;
    private int ingestedClasses = 0;

    private int targetFields = 150_000;
    private int ingestedFields = 0;

    private int targetMethods = 150_000;
    private int ingestedMethods = 0;

    private long targetRelationships = 15_000_000L;
    private long ingestedRelationships = 0L;

    private double rateRowsPerSec = 0.0;
    private double dbSizeMb = 0.0;
    private long heapUsedMb = 0L;

    private long startTime = 0L;
    private long endTime = 0L;
    private long durationMs = 0L;

    private String targetDir = "./codelens-stress-data";
    private String message = "";
    private String errorDetail = "";

    private Map<String, Object> benchmarkReport = new LinkedHashMap<>();

    public StressTestProgress() {}

    public StressTestProgress(Status status) {
        this.status = status;
    }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getActiveStage() { return activeStage; }
    public void setActiveStage(String activeStage) { this.activeStage = activeStage; }

    public String getCurrentPhase() { return currentPhase; }
    public void setCurrentPhase(String currentPhase) { this.currentPhase = currentPhase; }

    public String getCurrentDetail() { return currentDetail; }
    public void setCurrentDetail(String currentDetail) { this.currentDetail = currentDetail; }

    public int getPercentage() { return Math.min(100, Math.max(0, percentage)); }
    public void setPercentage(int percentage) { this.percentage = percentage; }

    public int getTargetClasses() { return targetClasses; }
    public void setTargetClasses(int targetClasses) { this.targetClasses = targetClasses; }

    public int getIngestedClasses() { return ingestedClasses; }
    public void setIngestedClasses(int ingestedClasses) { this.ingestedClasses = ingestedClasses; }

    public int getTargetFields() { return targetFields; }
    public void setTargetFields(int targetFields) { this.targetFields = targetFields; }

    public int getIngestedFields() { return ingestedFields; }
    public void setIngestedFields(int ingestedFields) { this.ingestedFields = ingestedFields; }

    public int getTargetMethods() { return targetMethods; }
    public void setTargetMethods(int targetMethods) { this.targetMethods = targetMethods; }

    public int getIngestedMethods() { return ingestedMethods; }
    public void setIngestedMethods(int ingestedMethods) { this.ingestedMethods = ingestedMethods; }

    public long getTargetRelationships() { return targetRelationships; }
    public void setTargetRelationships(long targetRelationships) { this.targetRelationships = targetRelationships; }

    public long getIngestedRelationships() { return ingestedRelationships; }
    public void setIngestedRelationships(long ingestedRelationships) { this.ingestedRelationships = ingestedRelationships; }

    public double getRateRowsPerSec() { return rateRowsPerSec; }
    public void setRateRowsPerSec(double rateRowsPerSec) { this.rateRowsPerSec = rateRowsPerSec; }

    public double getDbSizeMb() { return dbSizeMb; }
    public void setDbSizeMb(double dbSizeMb) { this.dbSizeMb = dbSizeMb; }

    public long getHeapUsedMb() { return heapUsedMb; }
    public void setHeapUsedMb(long heapUsedMb) { this.heapUsedMb = heapUsedMb; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getEndTime() { return endTime; }
    public void setEndTime(long endTime) { this.endTime = endTime; }

    public long getDurationMs() {
        if (durationMs > 0) return durationMs;
        if (startTime > 0) {
            return endTime > 0 ? (endTime - startTime) : (System.currentTimeMillis() - startTime);
        }
        return 0L;
    }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getTargetDir() { return targetDir; }
    public void setTargetDir(String targetDir) { this.targetDir = targetDir; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }

    public Map<String, Object> getBenchmarkReport() { return benchmarkReport != null ? benchmarkReport : Collections.emptyMap(); }
    public void setBenchmarkReport(Map<String, Object> benchmarkReport) { this.benchmarkReport = benchmarkReport; }
}
