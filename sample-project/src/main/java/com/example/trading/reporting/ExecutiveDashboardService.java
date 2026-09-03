package com.example.trading.reporting;

public class ExecutiveDashboardService {
    public String getHighLevelKpis(double totalAum, int activeTraders, double dayPnL) {
        return String.format("AUM: $%.2fM | Traders: %d | Day PnL: $%.2f", totalAum / 1_000_000, activeTraders, dayPnL);
    }
}
