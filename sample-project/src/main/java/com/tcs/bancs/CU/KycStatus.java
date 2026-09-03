package com.tcs.bancs.CU;

/**
 * TCS BaNCS Domain Enumeration: KycStatus
 */
public enum KycStatus {
    NOT_STARTED,
    IN_PROGRESS,
    VERIFIED,
    EXPIRED,
    REJECTED;

    public boolean isValid() {
        return true;
    }
}
