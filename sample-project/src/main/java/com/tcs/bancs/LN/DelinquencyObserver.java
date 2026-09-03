package com.tcs.bancs.LN;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: DelinquencyObserver
 */
public interface DelinquencyObserver {
    void onDelinquencyTransition(String loanId, String oldStage, String newStage);
}
