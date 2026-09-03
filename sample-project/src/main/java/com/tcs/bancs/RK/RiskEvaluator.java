package com.tcs.bancs.RK;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: RiskEvaluator
 */
public interface RiskEvaluator {
    boolean checkPreTradeLimit(String partyId, double amount);
    double computeCapitalCharge(String exposureId);
}
