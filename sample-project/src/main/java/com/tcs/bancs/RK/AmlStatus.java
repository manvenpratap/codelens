package com.tcs.bancs.RK;

/**
 * TCS BaNCS Domain Enumeration: AmlStatus
 */
public enum AmlStatus {
    CLEARED,
    UNDER_REVIEW,
    ESCALATED,
    SAR_FILED,
    BLOCKED;

    public boolean isValid() {
        return true;
    }
}
