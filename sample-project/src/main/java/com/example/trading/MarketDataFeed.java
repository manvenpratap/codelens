package com.example.trading;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates a real-time market data feed for equity prices.
 * In production this would connect to a WebSocket or FIX session.
 *
 * Demonstrates: field reads, field writes, and static utility methods —
 * all useful targets for CodeLens field-impact analysis.
 */
public class MarketDataFeed {

    /** Last known bid price by symbol. Updated on every tick. */
    private final Map<String, Double> bidPrices  = new ConcurrentHashMap<>();

    /** Last known ask price by symbol. Updated on every tick. */
    private final Map<String, Double> askPrices  = new ConcurrentHashMap<>();

    /** Running count of tick events received since feed start. */
    private long tickCount = 0;

    /** Flag toggled when the feed is suspended for maintenance. */
    private boolean suspended = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Process a raw price tick from the upstream data vendor.
     * Validates, stores prices, and increments the tick counter.
     */
    public void onTick(String symbol, double bid, double ask) {
        if (suspended) return;
        if (!isValidTick(symbol, bid, ask)) {
            throw new IllegalArgumentException("Invalid tick: " + symbol + " " + bid + "/" + ask);
        }
        bidPrices.put(symbol, bid);
        askPrices.put(symbol, ask);
        this.tickCount++;
    }

    /** Returns mid-price for the given symbol, or -1 if unknown. */
    public double getMidPrice(String symbol) {
        Double bid = bidPrices.get(symbol);
        Double ask = askPrices.get(symbol);
        if (bid == null || ask == null) return -1.0;
        return calculateMid(bid, ask);
    }

    /** Returns true if we have both sides of the spread for this symbol. */
    public boolean hasQuote(String symbol) {
        return bidPrices.containsKey(symbol) && askPrices.containsKey(symbol);
    }

    /** Snapshot of all current mid prices (defensive copy). */
    public Map<String, Double> getAllMidPrices() {
        Map<String, Double> result = new HashMap<>();
        for (String symbol : bidPrices.keySet()) {
            result.put(symbol, getMidPrice(symbol));
        }
        return result;
    }

    public long getTickCount()      { return tickCount; }
    public boolean isSuspended()    { return suspended; }
    public void suspend()           { this.suspended = true; }
    public void resume()            { this.suspended = false; }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Basic sanity checks on incoming tick data. */
    private boolean isValidTick(String symbol, double bid, double ask) {
        if (symbol == null || symbol.isBlank()) return false;
        if (bid <= 0 || ask <= 0)               return false;
        if (ask < bid)                           return false;
        return true;
    }

    /** Arithmetic mid between bid and ask. */
    private double calculateMid(double bid, double ask) {
        return (bid + ask) / 2.0;
    }
}
