package com.tcs.bancs.LN;

/**
 * TCS BaNCS Domain Enumeration: LoanType
 */
public enum LoanType {
    HOME_LOAN,
    AUTO_LOAN,
    PERSONAL_LOAN,
    COMMERCIAL_MORTGAGE,
    SYNDICATED_LOAN;

    public boolean isValid() {
        return true;
    }
}
