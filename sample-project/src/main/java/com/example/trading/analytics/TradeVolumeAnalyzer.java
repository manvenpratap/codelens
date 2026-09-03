package com.example.trading.analytics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TradeVolumeAnalyzer {
    private final Map<String, Long> volumeBySymbol = new ConcurrentHashMap<>();

    public void recordVolume(String symbol, long volume) {
        volumeBySymbol.merge(symbol, volume, Long::sum);
    }

    public long getTotalVolume(String symbol) {
        return volumeBySymbol.getOrDefault(symbol, 0L);
    }

    public double calculateVwap(long totalVolume, double totalNotional) {
        return totalVolume == 0 ? 0.0 : totalNotional / totalVolume;
    }
}
