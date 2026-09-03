package com.tcs.bancs.CL;

/**
 * TCS BaNCS Domain Enumeration: SettlementStatus
 */
public enum SettlementStatus {
    PENDING,
    AFFIRMED,
    MATCHED,
    SETTLED,
    FAILED,
    CANCELLED;

    public boolean isValid() {
        return true;
    }
}
