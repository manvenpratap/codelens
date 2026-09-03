package com.tcs.bancs.GL;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: TrialBalanceObserver
 */
public interface TrialBalanceObserver {
    void onUnbalancedConditionDetected(double drTotal, double crTotal);
}
