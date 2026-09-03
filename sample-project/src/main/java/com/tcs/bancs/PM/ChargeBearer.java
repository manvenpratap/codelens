package com.tcs.bancs.PM;

/**
 * TCS BaNCS Domain Enumeration: ChargeBearer
 */
public enum ChargeBearer {
    DEBT,
    CRED,
    SHAR,
    SLEV;

    public boolean isValid() {
        return true;
    }
}
