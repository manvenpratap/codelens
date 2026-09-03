package com.example.trading.analytics;

import java.util.HashMap;
import java.util.Map;

public class PnLReporter {
    private final Map<String, Double> realizedPnL = new HashMap<>();
    private final Map<String, Double> unrealizedPnL = new HashMap<>();

    public void updatePnL(String accountId, double realized, double unrealized) {
        realizedPnL.put(accountId, realized);
        unrealizedPnL.put(accountId, unrealized);
    }

    public double getTotalPnL(String accountId) {
        return realizedPnL.getOrDefault(accountId, 0.0) + unrealizedPnL.getOrDefault(accountId, 0.0);
    }
}
