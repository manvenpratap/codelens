package com.tcs.bancs.CL;

/**
 * TCS BaNCS Domain Enumeration: SettlementCycle
 */
public enum SettlementCycle {
    T_PLUS_0,
    T_PLUS_1,
    T_PLUS_2,
    SAME_DAY_RTGS;

    public boolean isValid() {
        return true;
    }
}
