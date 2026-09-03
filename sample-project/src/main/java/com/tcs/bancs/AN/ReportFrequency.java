package com.tcs.bancs.AN;

/**
 * TCS BaNCS Domain Enumeration: ReportFrequency
 */
public enum ReportFrequency {
    DAILY,
    MONTHLY,
    QUARTERLY,
    ANNUAL,
    AD_HOC;

    public boolean isValid() {
        return true;
    }
}
