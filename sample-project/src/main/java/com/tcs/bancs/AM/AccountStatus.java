package com.tcs.bancs.AM;

/**
 * TCS BaNCS Domain Enumeration: AccountStatus
 */
public enum AccountStatus {
    ACTIVE,
    DORMANT,
    FROZEN,
    CLOSED,
    UNDER_AUDIT;

    public boolean isValid() {
        return true;
    }
}
