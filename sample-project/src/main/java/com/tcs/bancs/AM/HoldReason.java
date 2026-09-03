package com.tcs.bancs.AM;

/**
 * TCS BaNCS Domain Enumeration: HoldReason
 */
public enum HoldReason {
    COURT_ORDER,
    SUSPECTED_FRAUD,
    MARGIN_PLEDGE,
    PENDING_CLEARING,
    TAX_LEVY;

    public boolean isValid() {
        return true;
    }
}
