package com.tcs.bancs.AM;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: InterestCalculator
 */
public interface InterestCalculator {
    double computeAccrual(double balance, double rate, int days);
    double computeOverdraftPenalty(double negativeBalance, double penaltyRate);
}
