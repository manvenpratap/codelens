package com.tcs.bancs.RK;

/**
 * TCS BaNCS Domain Enumeration: StressScenario
 */
public enum StressScenario {
    HISTORICAL_2008,
    COVID_SHOCK,
    RATE_HIKE_300BPS,
    LIQUIDITY_FREEZE;

    public boolean isValid() {
        return true;
    }
}
