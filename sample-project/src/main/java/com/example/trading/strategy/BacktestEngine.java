package com.example.trading.strategy;

import java.util.List;

public class BacktestEngine {
    public double runBacktest(TradingStrategy strategy, String symbol, List<Double> priceSeries) {
        double capital = 100_000.0;
        int shares = 0;
        for (double p : priceSeries) {
            SignalTrigger signal = strategy.evaluate(symbol, p);
            if (signal == SignalTrigger.BUY && capital >= p * 10) {
                shares += 10;
                capital -= p * 10;
            } else if (signal == SignalTrigger.SELL && shares >= 10) {
                shares -= 10;
                capital += p * 10;
            }
        }
        double finalPrice = priceSeries.isEmpty() ? 0.0 : priceSeries.get(priceSeries.size() - 1);
        return capital + (shares * finalPrice);
    }
}
