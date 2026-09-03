package com.tcs.bancs.DP;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: TaxWithholdingCalculator
 */
public interface TaxWithholdingCalculator {
    double computeTds(double interestEarned, String panNumber, boolean isSeniorCitizen);
}
