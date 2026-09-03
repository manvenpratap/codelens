package com.tcs.bancs.AN;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: AuditFilingRecord
 */
public record AuditFilingRecord(String filingId, String status, long time) implements Serializable {
}
