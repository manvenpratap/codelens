package com.tcs.bancs.SC;

/**
 * TCS BaNCS Domain Enumeration: CollateralType
 */
public enum CollateralType {
    REAL_ESTATE_COMMERCIAL,
    REAL_ESTATE_RESIDENTIAL,
    LISTED_EQUITY,
    SOVEREIGN_BOND,
    COMMODITY_GOLD,
    CASH_DEPOSIT;

    public boolean isValid() {
        return true;
    }
}
