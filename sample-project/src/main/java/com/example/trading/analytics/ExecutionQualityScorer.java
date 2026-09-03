package com.example.trading.analytics;

public class ExecutionQualityScorer {
    public double scoreExecution(double requestedPrice, double executedPrice, long latencyMs) {
        double priceDiffRatio = Math.abs(executedPrice - requestedPrice) / requestedPrice;
        double penalty = (priceDiffRatio * 100) + (latencyMs * 0.05);
        return Math.max(0.0, 100.0 - penalty);
    }
}
