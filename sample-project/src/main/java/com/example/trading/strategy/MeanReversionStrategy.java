package com.example.trading.strategy;

public class MeanReversionStrategy implements TradingStrategy {
    private final double movingAverage;

    public MeanReversionStrategy(double movingAverage) {
        this.movingAverage = movingAverage;
    }

    @Override
    public String getStrategyName() { return "MeanReversion"; }

    @Override
    public SignalTrigger evaluate(String symbol, double currentPrice) {
        if (currentPrice < movingAverage * 0.95) return SignalTrigger.BUY;
        if (currentPrice > movingAverage * 1.05) return SignalTrigger.SELL;
        return SignalTrigger.HOLD;
    }
}
