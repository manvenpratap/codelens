package com.tcs.bancs.PM;

/**
 * TCS BaNCS Domain Enumeration: MandateFrequency
 */
public enum MandateFrequency {
    ONE_OFF,
    WEEKLY,
    MONTHLY,
    QUARTERLY,
    ANNUALLY;

    public boolean isValid() {
        return true;
    }
}
