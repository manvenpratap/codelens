package com.example.trading.engine;

import com.example.trading.api.MarketFeedListener;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real-time quotes and level-2 tick streaming feed.
 */
public class MarketDataFeed {

    // Stored live mid-prices per symbol
    private final Map<String, Double> lastPrices = new ConcurrentHashMap<>();
    private final Map<String, Double> bidPrices = new ConcurrentHashMap<>();
    private final Map<String, Double> askPrices = new ConcurrentHashMap<>();
    private final List<MarketFeedListener> listeners = new ArrayList<>();

    public MarketDataFeed() {
        // Seed initial market quotes
        publishTick("AAPL", 185.20, 185.25, 185.22, 15000);
        publishTick("MSFT", 415.50, 415.60, 415.55, 12000);
        publishTick("NVDA", 124.80, 124.85, 124.82, 45000);
        publishTick("GOOGL", 178.10, 178.15, 178.12, 18000);
    }

    public synchronized void registerListener(MarketFeedListener listener) {
        listeners.add(listener);
    }

    public void publishTick(String symbol, double bid, double ask, double lastPrice, long volume) {
        bidPrices.put(symbol, bid);
        askPrices.put(symbol, ask);
        lastPrices.put(symbol, lastPrice);

        for (MarketFeedListener listener : listeners) {
            try {
                listener.onTick(symbol, bid, ask, lastPrice, volume);
            } catch (Exception e) {
                // Log and continue streaming
                System.err.println("Listener error for " + symbol + ": " + e.getMessage());
            }
        }
    }

    public double getMidPrice(String symbol) {
        Double bid = bidPrices.get(symbol);
        Double ask = askPrices.get(symbol);
        if (bid != null && ask != null) {
            return (bid + ask) / 2.0;
        }
        return lastPrices.getOrDefault(symbol, 0.0);
    }

    public double getBidPrice(String symbol) {
        return bidPrices.getOrDefault(symbol, 0.0);
    }

    public double getAskPrice(String symbol) {
        return askPrices.getOrDefault(symbol, 0.0);
    }

    public void parseReplayLog(String logData) {
        // Parse simulated CSV replay data
        try {
            BufferedReader reader = new BufferedReader(new StringReader(logData));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] tokens = line.split(",");
                if (tokens.length >= 5) {
                    publishTick(tokens[0], Double.parseDouble(tokens[1]),
                                Double.parseDouble(tokens[2]), Double.parseDouble(tokens[3]),
                                Long.parseLong(tokens[4]));
                }
            }
            reader.close();
        } catch (Exception e) {
            System.err.println("Replay parse error: " + e.getMessage());
        }
    }
}
