package com.codelens.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Summary of detected disk changes (new, modified, deleted .java files)
 * compared against the database index.
 */
public class ScanChanges {

    private String       sourcePath;
    private boolean      hasChanges;
    private List<String> newFiles      = new ArrayList<>();
    private List<String> modifiedFiles = new ArrayList<>();
    private List<String> deletedFiles  = new ArrayList<>();
    private int          totalChanges;
    private long         checkTimestamp;

    public ScanChanges() {
        this.checkTimestamp = System.currentTimeMillis();
    }

    public ScanChanges(String sourcePath) {
        this.sourcePath = sourcePath;
        this.checkTimestamp = System.currentTimeMillis();
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public boolean isHasChanges() {
        return hasChanges;
    }

    public void setHasChanges(boolean hasChanges) {
        this.hasChanges = hasChanges;
    }

    public List<String> getNewFiles() {
        return newFiles;
    }

    public void setNewFiles(List<String> newFiles) {
        this.newFiles = newFiles != null ? newFiles : new ArrayList<>();
        recompute();
    }

    public List<String> getModifiedFiles() {
        return modifiedFiles;
    }

    public void setModifiedFiles(List<String> modifiedFiles) {
        this.modifiedFiles = modifiedFiles != null ? modifiedFiles : new ArrayList<>();
        recompute();
    }

    public List<String> getDeletedFiles() {
        return deletedFiles;
    }

    public void setDeletedFiles(List<String> deletedFiles) {
        this.deletedFiles = deletedFiles != null ? deletedFiles : new ArrayList<>();
        recompute();
    }

    public int getTotalChanges() {
        return totalChanges;
    }

    public void setTotalChanges(int totalChanges) {
        this.totalChanges = totalChanges;
    }

    public long getCheckTimestamp() {
        return checkTimestamp;
    }

    public void setCheckTimestamp(long checkTimestamp) {
        this.checkTimestamp = checkTimestamp;
    }

    public void recompute() {
        this.totalChanges = this.newFiles.size() + this.modifiedFiles.size() + this.deletedFiles.size();
        this.hasChanges = this.totalChanges > 0;
    }
}
