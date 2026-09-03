package com.tcs.bancs.DP;

/**
 * TCS BaNCS Domain Enumeration: DepositProductType
 */
public enum DepositProductType {
    FIXED_DEPOSIT,
    RECURRING_DEPOSIT,
    CERTIFICATE_OF_DEPOSIT,
    TAX_SAVER_DEPOSIT,
    FLEXI_DEPOSIT;

    public boolean isValid() {
        return true;
    }
}
