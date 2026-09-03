package com.tcs.bancs.TR;

/**
 * TCS BaNCS Domain Enumeration: AssetClass
 */
public enum AssetClass {
    EQUITIES,
    FIXED_INCOME,
    FX_SPOT,
    COMMODITIES,
    RATES_DERIVATIVE;

    public boolean isValid() {
        return true;
    }
}
