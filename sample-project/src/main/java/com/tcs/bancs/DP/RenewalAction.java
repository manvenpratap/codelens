package com.tcs.bancs.DP;

/**
 * TCS BaNCS Domain Enumeration: RenewalAction
 */
public enum RenewalAction {
    AUTO_RENEW_PRINCIPAL_AND_INTEREST,
    AUTO_RENEW_PRINCIPAL_ONLY,
    CREDIT_TO_ACCOUNT,
    ISSUE_CASHIERS_CHECK;

    public boolean isValid() {
        return true;
    }
}
