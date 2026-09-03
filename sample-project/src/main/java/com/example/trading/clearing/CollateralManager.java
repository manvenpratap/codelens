package com.example.trading.clearing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CollateralManager {
    private final Map<String, Double> collateralBalances = new ConcurrentHashMap<>();

    public void depositCollateral(String accountId, double amount) {
        collateralBalances.merge(accountId, amount, Double::sum);
    }

    public boolean releaseCollateral(String accountId, double amount) {
        Double current = collateralBalances.get(accountId);
        if (current == null || current < amount) return false;
        collateralBalances.put(accountId, current - amount);
        return true;
    }

    public double getCollateral(String accountId) {
        return collateralBalances.getOrDefault(accountId, 0.0);
    }
}
