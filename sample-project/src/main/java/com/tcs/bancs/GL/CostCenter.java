package com.tcs.bancs.GL;

/**
 * TCS BaNCS Domain Enumeration: CostCenter
 */
public enum CostCenter {
    RETAIL_BANKING,
    WEALTH_MANAGEMENT,
    TREASURY_OPS,
    INFORMATION_TECH,
    COMPLIANCE;

    public boolean isValid() {
        return true;
    }
}
