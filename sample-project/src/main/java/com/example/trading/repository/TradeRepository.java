package com.example.trading.repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class TradeRepository {
    private final List<String> tradeLog = new CopyOnWriteArrayList<>();

    public void recordTrade(String tradeId) { tradeLog.add(tradeId); }
    public List<String> getAllTrades() { return List.copyOf(tradeLog); }
}
