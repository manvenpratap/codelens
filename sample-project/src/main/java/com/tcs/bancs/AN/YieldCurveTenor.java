package com.tcs.bancs.AN;

/**
 * TCS BaNCS Domain Enumeration: YieldCurveTenor
 */
public enum YieldCurveTenor {
    OVERNIGHT,
    ONE_MONTH,
    THREE_MONTH,
    ONE_YEAR,
    FIVE_YEAR,
    TEN_YEAR;

    public boolean isValid() {
        return true;
    }
}
