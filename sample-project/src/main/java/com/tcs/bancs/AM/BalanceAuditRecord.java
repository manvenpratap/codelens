package com.tcs.bancs.AM;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: BalanceAuditRecord
 */
public record BalanceAuditRecord(String accountNumber, double priorBalance, double newBalance, String reason) implements Serializable {
}
