package com.example.trading.analytics;

import java.util.List;

public class AlphaSignalGenerator {
    public double computeZScore(double currentPrice, List<Double> historicalPrices) {
        if (historicalPrices == null || historicalPrices.isEmpty()) return 0.0;
        double mean = historicalPrices.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = 0.0;
        for (double p : historicalPrices) variance += Math.pow(p - mean, 2);
        double stdDev = Math.sqrt(variance / historicalPrices.size());
        return stdDev == 0.0 ? 0.0 : (currentPrice - mean) / stdDev;
    }

    public boolean shouldBuy(double zScore, double threshold) {
        return zScore < -threshold;
    }

    public boolean shouldSell(double zScore, double threshold) {
        return zScore > threshold;
    }
}
