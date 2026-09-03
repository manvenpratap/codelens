package com.example.trading.feed;

import java.util.TreeMap;

public class OrderBookReconstructor {
    private final TreeMap<Double, Integer> bids = new TreeMap<>((a, b) -> Double.compare(b, a));
    private final TreeMap<Double, Integer> asks = new TreeMap<>();

    public void applyQuote(Level2Quote quote) {
        bids.put(quote.bidPrice(), quote.bidSize());
        asks.put(quote.askPrice(), quote.askSize());
    }

    public Double getBestBid() { return bids.isEmpty() ? null : bids.firstKey(); }
    public Double getBestAsk() { return asks.isEmpty() ? null : asks.firstKey(); }
}
