package com.tcs.bancs.AN;

/**
 * TCS BaNCS Domain Enumeration: CapitalTier
 */
public enum CapitalTier {
    COMMON_EQUITY_TIER_1,
    ADDITIONAL_TIER_1,
    TIER_2_CAPITAL;

    public boolean isValid() {
        return true;
    }
}
