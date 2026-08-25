package com.example.trading.api;

/**
 * Real-time event listener for streaming market ticks and level 2 book updates.
 * Demonstrates Interface kind with multi-method contracts in CodeLens.
 */
public interface MarketFeedListener {

    /** Invoked on price tick update for an instrument. */
    void onTick(String symbol, double bid, double ask, double lastPrice, long volume);

    /** Invoked when order book depth changes. */
    void onBookUpdate(String symbol, int levels, double spread);

    /** Invoked on market feed disconnect or data degradation. */
    void onFeedError(String symbol, String errorMessage, Throwable cause);
}
