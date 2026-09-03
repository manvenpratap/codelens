package com.tcs.bancs.CL;

/**
 * TCS BaNCS Domain Enumeration: ClearingModel
 */
public enum ClearingModel {
    CENTRAL_COUNTERPARTY,
    BILATERAL_GROSS,
    NET_PERIODIC;

    public boolean isValid() {
        return true;
    }
}
