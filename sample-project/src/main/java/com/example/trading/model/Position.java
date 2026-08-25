package com.example.trading.model;

import java.util.Objects;

/**
 * Holding details for an individual equity or derivative asset.
 */
public class Position {

    private final String symbol;
    private int shares;
    private double averageCost;
    private double currentMarketPrice;
    private double realizedPnl;

    public Position(String symbol) {
        this.symbol = symbol;
        this.shares = 0;
        this.averageCost = 0.0;
        this.currentMarketPrice = 0.0;
        this.realizedPnl = 0.0;
    }

    public synchronized void updatePosition(int tradeQty, double fillPrice) {
        if (tradeQty == 0) return;

        if (shares == 0) {
            shares = tradeQty;
            averageCost = fillPrice;
        } else if ((shares > 0 && tradeQty > 0) || (shares < 0 && tradeQty < 0)) {
            // Increasing exposure
            double totalCost = (shares * averageCost) + (tradeQty * fillPrice);
            shares += tradeQty;
            averageCost = totalCost / shares;
        } else {
            // Reducing exposure / closing position
            int closedQty = Math.min(Math.abs(shares), Math.abs(tradeQty));
            double tradePnl = (shares > 0)
                ? (fillPrice - averageCost) * closedQty
                : (averageCost - fillPrice) * closedQty;
            realizedPnl += tradePnl;
            shares += tradeQty;
            if (shares == 0) {
                averageCost = 0.0;
            }
        }
        currentMarketPrice = fillPrice;
    }

    public synchronized void updateMarketPrice(double price) {
        this.currentMarketPrice = price;
    }

    public synchronized double getMarketValue() {
        return shares * currentMarketPrice;
    }

    public synchronized double getUnrealizedPnl() {
        if (shares == 0) return 0.0;
        return (currentMarketPrice - averageCost) * shares;
    }

    public synchronized double getTotalPnl() {
        return realizedPnl + getUnrealizedPnl();
    }

    public String getSymbol() { return symbol; }
    public synchronized int getShares() { return shares; }
    public synchronized double getAverageCost() { return averageCost; }
    public synchronized double getCurrentMarketPrice() { return currentMarketPrice; }
    public synchronized double getRealizedPnl() { return realizedPnl; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Position position = (Position) o;
        return Objects.equals(symbol, position.symbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol);
    }
}
