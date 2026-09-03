package com.tcs.bancs.TR;

/**
 * TCS BaNCS Domain Enumeration: TradeExecutionStatus
 */
public enum TradeExecutionStatus {
    PENDING,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED;

    public boolean isValid() {
        return true;
    }
}
