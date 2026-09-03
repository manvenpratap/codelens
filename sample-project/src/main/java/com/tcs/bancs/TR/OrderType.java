package com.tcs.bancs.TR;

/**
 * TCS BaNCS Domain Enumeration: OrderType
 */
public enum OrderType {
    MARKET,
    LIMIT,
    STOP_LIMIT,
    TRAILING_STOP,
    ICEBERG;

    public boolean isValid() {
        return true;
    }
}
