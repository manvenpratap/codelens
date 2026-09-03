package com.example.trading.clearing;

import java.util.Map;
import java.util.HashMap;

public class NettingProcessor {
    public Map<String, Integer> calculateNetPositions(Map<String, Integer> grossBuy, Map<String, Integer> grossSell) {
        Map<String, Integer> net = new HashMap<>();
        for (String symbol : grossBuy.keySet()) {
            int buy = grossBuy.getOrDefault(symbol, 0);
            int sell = grossSell.getOrDefault(symbol, 0);
            net.put(symbol, buy - sell);
        }
        return net;
    }
}
