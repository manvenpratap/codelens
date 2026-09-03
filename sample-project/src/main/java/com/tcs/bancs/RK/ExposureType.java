package com.tcs.bancs.RK;

/**
 * TCS BaNCS Domain Enumeration: ExposureType
 */
public enum ExposureType {
    MARKET_RISK,
    CREDIT_DEFAULT,
    SETTLEMENT_RISK,
    OPERATIONAL_RISK;

    public boolean isValid() {
        return true;
    }
}
