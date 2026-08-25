package com.example.trading.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Account portfolio holding cash, leverage balances, and active positions.
 * Provides rich field access patterns for CodeLens field blast-radius analysis.
 */
public class Portfolio {

    private final String accountId;
    private double cashBalance;
    private double initialCapital;
    private double marginUsed;
    private double maxDrawdownLimit;
    private final Map<String, Position> positions;

    public Portfolio(String accountId, double initialCapital, double maxDrawdownLimit) {
        this.accountId = accountId;
        this.cashBalance = initialCapital;
        this.initialCapital = initialCapital;
        this.marginUsed = 0.0;
        this.maxDrawdownLimit = maxDrawdownLimit;
        this.positions = new HashMap<>();
    }

    public synchronized void recordFill(String symbol, int quantity, double fillPrice, double commission) {
        cashBalance -= (quantity * fillPrice + commission);
        Position pos = positions.computeIfAbsent(symbol, Position::new);
        pos.updatePosition(quantity, fillPrice);
        recalculateMargin();
    }

    public synchronized void updateMarketPrice(String symbol, double price) {
        Position pos = positions.get(symbol);
        if (pos != null) {
            pos.updateMarketPrice(price);
            recalculateMargin();
        }
    }

    private void recalculateMargin() {
        double totalExposure = 0.0;
        for (Position p : positions.values()) {
            totalExposure += Math.abs(p.getMarketValue());
        }
        this.marginUsed = totalExposure * 0.25; // 4x leverage requirement
    }

    public synchronized double getTotalEquity() {
        double unrealized = 0.0;
        for (Position p : positions.values()) {
            unrealized += p.getUnrealizedPnl();
        }
        return cashBalance + unrealized;
    }

    public synchronized double getAvailableMargin() {
        return getTotalEquity() - marginUsed;
    }

    public synchronized double getCurrentDrawdownPercent() {
        double equity = getTotalEquity();
        if (equity >= initialCapital) return 0.0;
        return ((initialCapital - equity) / initialCapital) * 100.0;
    }

    public String getAccountId() { return accountId; }
    public synchronized double getCashBalance() { return cashBalance; }
    public synchronized double getMarginUsed() { return marginUsed; }
    public double getMaxDrawdownLimit() { return maxDrawdownLimit; }
    public synchronized Map<String, Position> getPositions() {
        return Collections.unmodifiableMap(positions);
    }
}
