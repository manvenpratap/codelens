package com.codelens.core.model;

import java.util.Objects;

/**
 * Metadata record for an indexed source file.
 */
public class FileMeta {

    private String filePath;
    private long   lastModified;
    private long   fileSize;
    private int    typeCount;

    public FileMeta() {}

    public FileMeta(String filePath, long lastModified, long fileSize, int typeCount) {
        this.filePath = filePath;
        this.lastModified = lastModified;
        this.fileSize = fileSize;
        this.typeCount = typeCount;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public int getTypeCount() {
        return typeCount;
    }

    public void setTypeCount(int typeCount) {
        this.typeCount = typeCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileMeta that)) return false;
        return Objects.equals(filePath, that.filePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filePath);
    }

    @Override
    public String toString() {
        return "FileMeta{" +
                "filePath='" + filePath + '\'' +
                ", lastModified=" + lastModified +
                ", fileSize=" + fileSize +
                ", typeCount=" + typeCount +
                '}';
    }
}
