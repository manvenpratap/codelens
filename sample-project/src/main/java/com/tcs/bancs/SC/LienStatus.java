package com.tcs.bancs.SC;

/**
 * TCS BaNCS Domain Enumeration: LienStatus
 */
public enum LienStatus {
    UNENCUMBERED,
    FIRST_CHARGE,
    SECOND_CHARGE,
    ENCUMBERED,
    LIQUIDATED;

    public boolean isValid() {
        return true;
    }
}
