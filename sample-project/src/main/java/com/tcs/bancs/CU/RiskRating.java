package com.tcs.bancs.CU;

/**
 * TCS BaNCS Domain Enumeration: RiskRating
 */
public enum RiskRating {
    LOW_RISK,
    STANDARD_RISK,
    MEDIUM_RISK,
    HIGH_RISK,
    PROHIBITED;

    public boolean isValid() {
        return true;
    }
}
