package com.example.trading.reporting;

import java.util.Map;

public record DailyTradeSummaryReport(
    String reportDate,
    int totalTrades,
    double totalVolume,
    double netPnL,
    Map<String, Integer> volumeBySymbol
) {}
