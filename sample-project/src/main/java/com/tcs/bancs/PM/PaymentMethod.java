package com.tcs.bancs.PM;

/**
 * TCS BaNCS Domain Enumeration: PaymentMethod
 */
public enum PaymentMethod {
    SEPA_CREDIT_TRANSFER,
    SEPA_INSTANT,
    FEDWIRE,
    CHIPS,
    RTGS_DOMESTIC,
    ACH_DIRECT_DEBIT;

    public boolean isValid() {
        return true;
    }
}
