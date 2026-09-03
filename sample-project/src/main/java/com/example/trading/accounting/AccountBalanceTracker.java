package com.example.trading.accounting;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AccountBalanceTracker {
    private final Map<String, Double> cashBalances = new ConcurrentHashMap<>();

    public void credit(String accountId, double amount) {
        cashBalances.merge(accountId, amount, Double::sum);
    }

    public boolean debit(String accountId, double amount) {
        Double balance = cashBalances.get(accountId);
        if (balance == null || balance < amount) return false;
        cashBalances.put(accountId, balance - amount);
        return true;
    }

    public double getBalance(String accountId) {
        return cashBalances.getOrDefault(accountId, 0.0);
    }
}
