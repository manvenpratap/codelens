package com.example.trading.accounting;

public class FeeCalculationEngine {
    private static final double BASE_FEE_RATE = 0.0005;

    public double calculateCommission(double notional) {
        return Math.max(1.0, notional * BASE_FEE_RATE);
    }

    public double calculateExchangeFee(int shares) {
        return shares * 0.0015;
    }
}
