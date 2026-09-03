package com.tcs.bancs.DP;

/**
 * TCS BaNCS Domain Enumeration: CompoundingFrequency
 */
public enum CompoundingFrequency {
    MONTHLY,
    QUARTERLY,
    HALF_YEARLY,
    ANNUALLY,
    AT_MATURITY;

    public boolean isValid() {
        return true;
    }
}
