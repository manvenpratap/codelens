package com.example.trading.analytics;

import java.util.List;

public class VolatilityEstimator {
    public double estimateHistoricalVolatility(List<Double> prices) {
        if (prices == null || prices.size() < 2) return 0.0;
        double sumReturns = 0.0;
        double[] logReturns = new double[prices.size() - 1];
        for (int i = 1; i < prices.size(); i++) {
            logReturns[i - 1] = Math.log(prices.get(i) / prices.get(i - 1));
            sumReturns += logReturns[i - 1];
        }
        double meanReturn = sumReturns / logReturns.length;
        double variance = 0.0;
        for (double lr : logReturns) {
            variance += Math.pow(lr - meanReturn, 2);
        }
        return Math.sqrt((variance / logReturns.length) * 252);
    }
}
