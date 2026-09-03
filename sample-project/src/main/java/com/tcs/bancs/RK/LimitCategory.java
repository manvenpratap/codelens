package com.tcs.bancs.RK;

/**
 * TCS BaNCS Domain Enumeration: LimitCategory
 */
public enum LimitCategory {
    INTRADAY_SETTLEMENT,
    CREDIT_LINE,
    TENOR_LIMIT,
    COUNTRY_LIMIT,
    NOTIONAL_CAP;

    public boolean isValid() {
        return true;
    }
}
