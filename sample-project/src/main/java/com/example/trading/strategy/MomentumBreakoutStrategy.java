package com.example.trading.strategy;

public class MomentumBreakoutStrategy implements TradingStrategy {
    private final double resistanceLevel;

    public MomentumBreakoutStrategy(double resistanceLevel) {
        this.resistanceLevel = resistanceLevel;
    }

    @Override
    public String getStrategyName() { return "MomentumBreakout"; }

    @Override
    public SignalTrigger evaluate(String symbol, double currentPrice) {
        return currentPrice > resistanceLevel ? SignalTrigger.BUY : SignalTrigger.HOLD;
    }
}
