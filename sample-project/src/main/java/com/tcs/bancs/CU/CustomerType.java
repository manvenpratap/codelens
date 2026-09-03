package com.tcs.bancs.CU;

/**
 * TCS BaNCS Domain Enumeration: CustomerType
 */
public enum CustomerType {
    INDIVIDUAL,
    CORPORATE,
    FINANCIAL_INSTITUTION,
    GOVERNMENT_ENTITY,
    TRUST;

    public boolean isValid() {
        return true;
    }
}
