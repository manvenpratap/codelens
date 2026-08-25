package com.example.trading.engine;

/**
 * Quantitative pricing models including Black-Scholes volatility calculations.
 */
public class PricingEngine {

    private double riskFreeRate = 0.045; // 4.5% Federal Funds benchmark

    public double calculateTheoreticalValue(double spot, double strike, double timeToExpiry, double volatility, boolean isCall) {
        if (timeToExpiry <= 0) {
            return isCall ? Math.max(0, spot - strike) : Math.max(0, strike - spot);
        }

        double d1 = (Math.log(spot / strike) + (riskFreeRate + 0.5 * volatility * volatility) * timeToExpiry)
                    / (volatility * Math.sqrt(timeToExpiry));
        double d2 = d1 - volatility * Math.sqrt(timeToExpiry);

        double nd1 = cumulativeNormalDistribution(isCall ? d1 : -d1);
        double nd2 = cumulativeNormalDistribution(isCall ? d2 : -d2);

        if (isCall) {
            return spot * nd1 - strike * Math.exp(-riskFreeRate * timeToExpiry) * nd2;
        } else {
            return strike * Math.exp(-riskFreeRate * timeToExpiry) * nd2 - spot * nd1;
        }
    }

    public double calculateSlippage(int quantity, double averageDailyVolume, double volatility) {
        double participationRate = Math.abs((double) quantity) / Math.max(1000.0, averageDailyVolume);
        return 0.1 * volatility * Math.sqrt(participationRate);
    }

    private double cumulativeNormalDistribution(double z) {
        double b1 = 0.319381530;
        double b2 = -0.356563782;
        double b3 = 1.781477937;
        double b4 = -1.821255978;
        double b5 = 1.330274429;
        double p = 0.2316419;
        double c2 = 0.39894228;

        if (z >= 0.0) {
            double t = 1.0 / (1.0 + p * z);
            return (1.0 - c2 * Math.exp(-z * z / 2.0) * t * (t * (t * (t * (t * b5 + b4) + b3) + b2) + b1));
        } else {
            double t = 1.0 / (1.0 - p * z);
            return (c2 * Math.exp(-z * z / 2.0) * t * (t * (t * (t * (t * b5 + b4) + b3) + b2) + b1));
        }
    }

    public double getRiskFreeRate() { return riskFreeRate; }
    public void setRiskFreeRate(double rate) { this.riskFreeRate = rate; }
}
