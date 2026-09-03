package com.tcs.bancs.AN;

/**
 * TCS BaNCS Domain Enumeration: AttributionCategory
 */
public enum AttributionCategory {
    ASSET_ALLOCATION,
    SECURITY_SELECTION,
    CURRENCY_EFFECT,
    INTEREST_RATE_SHIFT;

    public boolean isValid() {
        return true;
    }
}
