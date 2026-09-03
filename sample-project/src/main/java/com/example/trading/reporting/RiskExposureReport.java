package com.example.trading.reporting;

public class RiskExposureReport {
    public String generateSummary(String accountId, double var99, double leverage) {
        return String.format("Account: %s | 99%% VaR: $%.2f | Leverage: %.2fx", accountId, var99, leverage);
    }
}
