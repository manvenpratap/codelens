package com.tcs.bancs.LN;

/**
 * TCS BaNCS Domain Enumeration: LoanStatus
 */
public enum LoanStatus {
    APPLIED,
    SANCTIONED,
    ACTIVE,
    DELINQUENT,
    CLOSED,
    WRITTEN_OFF;

    public boolean isValid() {
        return true;
    }
}
