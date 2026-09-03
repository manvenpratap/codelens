package com.tcs.bancs.LN;

/**
 * TCS BaNCS Domain Enumeration: DisbursementStatus
 */
public enum DisbursementStatus {
    PENDING,
    APPROVED,
    DISBURSED,
    REJECTED,
    CANCELLED;

    public boolean isValid() {
        return true;
    }
}
