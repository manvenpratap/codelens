package com.tcs.bancs.LN;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: DisbursementTrancheRecord
 */
public record DisbursementTrancheRecord(String trancheId, String loanId, double amount) implements Serializable {
}
