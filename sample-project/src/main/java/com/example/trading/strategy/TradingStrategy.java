package com.example.trading.strategy;

public interface TradingStrategy {
    String getStrategyName();
    SignalTrigger evaluate(String symbol, double currentPrice);
}
