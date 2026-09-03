package com.tcs.bancs.LN;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: RepaymentReceiptRecord
 */
public record RepaymentReceiptRecord(String receiptId, String loanId, double amount, long timestamp) implements Serializable {
}
