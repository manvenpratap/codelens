package com.tcs.bancs.GL;

/**
 * TCS BaNCS Domain Enumeration: PeriodStatus
 */
public enum PeriodStatus {
    FUTURE,
    OPEN,
    SOFT_LOCKED,
    LOCKED,
    CLOSED;

    public boolean isValid() {
        return true;
    }
}
