package com.tcs.bancs.AM;

/**
 * TCS BaNCS Domain Enumeration: AccrualMethod
 */
public enum AccrualMethod {
    SIMPLE_DAILY,
    MONTHLY_COMPOUND,
    QUARTERLY_AVERAGE,
    YEAR_END;

    public boolean isValid() {
        return true;
    }
}
