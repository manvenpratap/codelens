package com.tcs.bancs.common;

import java.io.Serializable;

public record AuditEntryRecord(String category, String source, String correlationId, String detail, long timestamp) implements Serializable {
}
