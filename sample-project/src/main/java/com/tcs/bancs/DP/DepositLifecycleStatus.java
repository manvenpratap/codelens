package com.tcs.bancs.DP;

/**
 * TCS BaNCS Domain Enumeration: DepositLifecycleStatus
 */
public enum DepositLifecycleStatus {
    OPEN,
    ACTIVE,
    MATURED,
    PREMATURELY_CLOSED,
    TRANSFERRED;

    public boolean isValid() {
        return true;
    }
}
