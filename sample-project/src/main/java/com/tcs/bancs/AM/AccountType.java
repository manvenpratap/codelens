package com.tcs.bancs.AM;

/**
 * TCS BaNCS Domain Enumeration: AccountType
 */
public enum AccountType {
    SAVINGS,
    CURRENT,
    CORPORATE,
    ESCROW,
    NOSTRO;

    public boolean isValid() {
        return true;
    }
}
