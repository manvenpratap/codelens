package com.tcs.bancs.DP;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: MaturityNotificationListener
 */
public interface MaturityNotificationListener {
    void onMaturityDue(String depositId, String customerId, double maturityAmount);
}
