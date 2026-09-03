package com.tcs.bancs.AN;

/**
 * TCS BaNCS Domain Enumeration: CalculationMethodology
 */
public enum CalculationMethodology {
    HISTORICAL_SIMULATION,
    MONTE_CARLO,
    PARAMETRIC_VARIANCE,
    SENSITIVITY_DELTA;

    public boolean isValid() {
        return true;
    }
}
