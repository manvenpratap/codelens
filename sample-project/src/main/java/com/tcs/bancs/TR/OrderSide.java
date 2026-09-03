package com.tcs.bancs.TR;

/**
 * TCS BaNCS Domain Enumeration: OrderSide
 */
public enum OrderSide {
    BUY,
    SELL,
    SELL_SHORT,
    BUY_TO_COVER;

    public boolean isValid() {
        return true;
    }
}
