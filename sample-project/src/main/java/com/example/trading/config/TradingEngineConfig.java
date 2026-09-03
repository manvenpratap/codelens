package com.example.trading.config;

public class TradingEngineConfig {
    private int maxOrderRatePerSecond = 5000;
    private boolean strictRiskChecking = true;

    public int getMaxOrderRatePerSecond() { return maxOrderRatePerSecond; }
    public void setMaxOrderRatePerSecond(int rate) { this.maxOrderRatePerSecond = rate; }
    public boolean isStrictRiskChecking() { return strictRiskChecking; }
}
