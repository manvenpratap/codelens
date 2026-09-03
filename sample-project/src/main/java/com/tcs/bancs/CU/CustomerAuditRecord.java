package com.tcs.bancs.CU;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: CustomerAuditRecord
 */
public record CustomerAuditRecord(String customerId, String fieldChanged, long time) implements Serializable {
}
