package com.tcs.bancs.PM;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: PaymentRouter
 */
public interface PaymentRouter {
    String selectOptimalRoute(String debtorBic, String creditorBic, double amount, String currency);
}
