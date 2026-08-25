package com.example.trading.service;

import com.example.trading.engine.MarketDataFeed;
import com.example.trading.model.Portfolio;
import com.example.trading.model.Position;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages institutional and retail trading portfolios, valuations, and risk limits.
 */
public class PortfolioManager {

    private final Map<String, Portfolio> accountPortfolios = new ConcurrentHashMap<>();
    private final MarketDataFeed marketDataFeed;

    public PortfolioManager(MarketDataFeed marketDataFeed) {
        this.marketDataFeed = marketDataFeed;
    }

    public Portfolio registerAccount(String accountId, double initialCapital, double maxDrawdown) {
        Portfolio p = new Portfolio(accountId, initialCapital, maxDrawdown);
        accountPortfolios.put(accountId, p);
        return p;
    }

    public Portfolio getPortfolio(String accountId) {
        return accountPortfolios.get(accountId);
    }

    public void updateAllValuations() {
        for (Portfolio portfolio : accountPortfolios.values()) {
            for (Position pos : portfolio.getPositions().values()) {
                double mid = marketDataFeed.getMidPrice(pos.getSymbol());
                if (mid > 0) {
                    portfolio.updateMarketPrice(pos.getSymbol(), mid);
                }
            }
        }
    }

    public double getAggregateFirmAUM() {
        double total = 0.0;
        for (Portfolio p : accountPortfolios.values()) {
            total += p.getTotalEquity();
        }
        return total;
    }
}
