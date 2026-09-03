package com.example.trading.strategy;

public class MarketMakerStrategy implements TradingStrategy {
    private final double spread;

    public MarketMakerStrategy(double spread) {
        this.spread = spread;
    }

    @Override
    public String getStrategyName() { return "MarketMaker"; }

    @Override
    public SignalTrigger evaluate(String symbol, double currentPrice) {
        return SignalTrigger.HOLD;
    }

    public double getQuoteBid(double midPrice) { return midPrice - (spread / 2); }
    public double getQuoteAsk(double midPrice) { return midPrice + (spread / 2); }
}
