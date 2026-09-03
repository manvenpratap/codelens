package com.tcs.bancs.TR;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: MatchingEngineListener
 */
public interface MatchingEngineListener {
    void onOrderMatched(String orderId, String tradeId, int qty, double price);
}
