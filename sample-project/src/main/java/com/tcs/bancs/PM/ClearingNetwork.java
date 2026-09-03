package com.tcs.bancs.PM;

/**
 * TCS BaNCS Domain Enumeration: ClearingNetwork
 */
public enum ClearingNetwork {
    EBA_STEP2,
    TARGET2,
    FEDNOW,
    SWIFT_GPI,
    LOCAL_ACH;

    public boolean isValid() {
        return true;
    }
}
