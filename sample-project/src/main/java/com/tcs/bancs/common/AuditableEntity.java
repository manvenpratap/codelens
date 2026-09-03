package com.tcs.bancs.common;

public interface AuditableEntity {
    String getEntityKey();
    long getLastModifiedTime();
}
