package com.tcs.bancs.CL;

/**
 * TCS BaNCS Domain Enumeration: DepositoryType
 */
public enum DepositoryType {
    CENTRAL_SECURITIES_DEPOSITORY,
    INTERNATIONAL_CSD,
    SUB_CUSTODIAN;

    public boolean isValid() {
        return true;
    }
}
