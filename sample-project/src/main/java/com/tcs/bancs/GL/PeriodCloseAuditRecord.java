package com.tcs.bancs.GL;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: PeriodCloseAuditRecord
 */
public record PeriodCloseAuditRecord(String periodId, String user, long time) implements Serializable {
}
