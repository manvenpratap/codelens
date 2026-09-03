package com.example.trading.repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MarketDataRepository {
    private final Map<String, Double> lastPrices = new ConcurrentHashMap<>();

    public void updatePrice(String symbol, double price) { lastPrices.put(symbol, price); }
    public double getPrice(String symbol) { return lastPrices.getOrDefault(symbol, 0.0); }
}
