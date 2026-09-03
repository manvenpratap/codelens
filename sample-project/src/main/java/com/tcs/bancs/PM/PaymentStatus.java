package com.tcs.bancs.PM;

/**
 * TCS BaNCS Domain Enumeration: PaymentStatus
 */
public enum PaymentStatus {
    INITIATED,
    ROUTED,
    AUTHORIZED,
    PENDING_SETTLEMENT,
    SETTLED,
    REJECTED,
    REVERSED;

    public boolean isValid() {
        return true;
    }
}
