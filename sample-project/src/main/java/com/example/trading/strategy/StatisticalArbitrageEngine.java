package com.example.trading.strategy;

public class StatisticalArbitrageEngine {
    public double calculateSpread(double priceA, double priceB, double hedgeRatio) {
        return priceA - (hedgeRatio * priceB);
    }
}
