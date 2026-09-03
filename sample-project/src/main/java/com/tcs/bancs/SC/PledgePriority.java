package com.tcs.bancs.SC;

/**
 * TCS BaNCS Domain Enumeration: PledgePriority
 */
public enum PledgePriority {
    SENIOR_SECURED,
    PARI_PASSU,
    SUBORDINATED,
    MEZZANINE;

    public boolean isValid() {
        return true;
    }
}
