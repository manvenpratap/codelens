package com.tcs.bancs.LN;

/**
 * TCS BaNCS Domain Enumeration: AmortizationType
 */
public enum AmortizationType {
    EQUAL_MONTHLY_INSTALLMENT,
    BALLOON_PAYMENT,
    INTEREST_ONLY,
    STEP_UP_REPAYMENT;

    public boolean isValid() {
        return true;
    }
}
