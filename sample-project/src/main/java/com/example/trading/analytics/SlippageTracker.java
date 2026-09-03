package com.example.trading.analytics;

public class SlippageTracker {
    private double cumulativeSlippage = 0.0;
    private int tradeCount = 0;

    public synchronized void recordSlippage(double expectedPrice, double executedPrice, int quantity) {
        double slippagePerShare = Math.abs(executedPrice - expectedPrice);
        cumulativeSlippage += slippagePerShare * Math.abs(quantity);
        tradeCount++;
    }

    public synchronized double getAverageSlippage() {
        return tradeCount == 0 ? 0.0 : cumulativeSlippage / tradeCount;
    }
}
