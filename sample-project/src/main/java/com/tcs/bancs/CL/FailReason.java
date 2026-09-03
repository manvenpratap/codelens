package com.tcs.bancs.CL;

/**
 * TCS BaNCS Domain Enumeration: FailReason
 */
public enum FailReason {
    SECURITIES_SHORTFALL,
    CASH_SHORTFALL,
    INSTRUCTION_MISMATCH,
    SSI_INVALID;

    public boolean isValid() {
        return true;
    }
}
