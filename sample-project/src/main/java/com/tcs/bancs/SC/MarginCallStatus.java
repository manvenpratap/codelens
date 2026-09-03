package com.tcs.bancs.SC;

/**
 * TCS BaNCS Domain Enumeration: MarginCallStatus
 */
public enum MarginCallStatus {
    PENDING,
    ISSUED,
    SATISFIED,
    EXTENDED,
    DEFAULTED;

    public boolean isValid() {
        return true;
    }
}
