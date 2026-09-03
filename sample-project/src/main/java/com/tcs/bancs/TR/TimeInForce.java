package com.tcs.bancs.TR;

/**
 * TCS BaNCS Domain Enumeration: TimeInForce
 */
public enum TimeInForce {
    DAY,
    GTC,
    IOC,
    FOK,
    AT_THE_OPEN;

    public boolean isValid() {
        return true;
    }
}
