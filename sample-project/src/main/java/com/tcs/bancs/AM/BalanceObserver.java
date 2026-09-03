package com.tcs.bancs.AM;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: BalanceObserver
 */
public interface BalanceObserver {
    void onBalanceChanged(String accountNumber, double oldBal, double newBal);
}
