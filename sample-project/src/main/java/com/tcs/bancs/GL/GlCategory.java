package com.tcs.bancs.GL;

/**
 * TCS BaNCS Domain Enumeration: GlCategory
 */
public enum GlCategory {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE,
    CONTRA_ASSET;

    public boolean isValid() {
        return true;
    }
}
