package com.tcs.bancs.DP;

/**
 * TCS BaNCS Domain Enumeration: InterestPayoutMode
 */
public enum InterestPayoutMode {
    MONTHLY_PAYOUT,
    QUARTERLY_PAYOUT,
    CUMULATIVE_GROWTH;

    public boolean isValid() {
        return true;
    }
}
