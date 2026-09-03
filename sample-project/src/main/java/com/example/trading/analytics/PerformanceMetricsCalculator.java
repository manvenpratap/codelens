package com.example.trading.analytics;

import java.util.List;

public class PerformanceMetricsCalculator {
    public double calculateSharpeRatio(List<Double> returns, double riskFreeRate) {
        if (returns == null || returns.isEmpty()) return 0.0;
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = 0.0;
        for (double r : returns) variance += Math.pow(r - mean, 2);
        double stdDev = Math.sqrt(variance / returns.size());
        return stdDev == 0.0 ? 0.0 : (mean - riskFreeRate) / stdDev;
    }

    public double calculateMaxDrawdown(List<Double> equityCurve) {
        if (equityCurve == null || equityCurve.isEmpty()) return 0.0;
        double peak = equityCurve.get(0);
        double maxDd = 0.0;
        for (double val : equityCurve) {
            if (val > peak) peak = val;
            double dd = (peak - val) / peak;
            if (dd > maxDd) maxDd = dd;
        }
        return maxDd;
    }
}
