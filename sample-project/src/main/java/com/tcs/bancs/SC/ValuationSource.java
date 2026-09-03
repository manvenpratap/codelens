package com.tcs.bancs.SC;

/**
 * TCS BaNCS Domain Enumeration: ValuationSource
 */
public enum ValuationSource {
    INDEPENDENT_APPRAISAL,
    AUTOMATED_VALUATION_MODEL,
    MARKET_TICKER,
    REGULATORY_SCHEDULE;

    public boolean isValid() {
        return true;
    }
}
