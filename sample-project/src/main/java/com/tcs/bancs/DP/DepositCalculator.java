package com.tcs.bancs.DP;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: DepositCalculator
 */
public interface DepositCalculator {
    double computeMaturityValue(double principal, double rate, int days, String compounding);
    double calculateBreakPenalty(double principal, double rate, int elapsedDays);
}
