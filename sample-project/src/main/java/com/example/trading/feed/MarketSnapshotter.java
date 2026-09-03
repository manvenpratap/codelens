package com.example.trading.feed;

import java.util.Map;
import java.util.HashMap;

public class MarketSnapshotter {
    public Map<String, Object> createSnapshot(String symbol, double price, long volume) {
        Map<String, Object> map = new HashMap<>();
        map.put("symbol", symbol);
        map.put("price", price);
        map.put("volume", volume);
        map.put("timestamp", System.currentTimeMillis());
        return map;
    }
}
