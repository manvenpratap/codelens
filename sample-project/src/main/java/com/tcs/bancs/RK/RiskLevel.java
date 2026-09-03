package com.tcs.bancs.RK;

/**
 * TCS BaNCS Domain Enumeration: RiskLevel
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
    UNACCEPTABLE;

    public boolean isValid() {
        return true;
    }
}
