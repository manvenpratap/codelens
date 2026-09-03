package com.tcs.bancs.MS;

/**
 * TCS BaNCS Domain Enumeration: ProcessingState
 */
public enum ProcessingState {
    RECEIVED,
    PARSED,
    VALIDATED,
    DISPATCHED,
    FAILED;

    public boolean isValid() {
        return true;
    }
}
