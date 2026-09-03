package com.tcs.bancs.PM;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: PaymentAuditRecord
 */
public record PaymentAuditRecord(String paymentId, String event, long time) implements Serializable {
}
