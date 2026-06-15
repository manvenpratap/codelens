package com.example.trading;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents an equity portfolio with position management.
 *
 * A "position" is stored as net quantity (positive = long, negative = short).
 * This class demonstrates: fields read/written by multiple methods — good for
 * CodeLens field-impact analysis showing all code paths that touch 'positions'
 * and 'cashBalance'.
 */
public class Portfolio {

    private final String portfolioId;

    /**
     * Net quantity per symbol.
     * Written by updatePosition(); read by getPosition(), getValue().
     */
    private final Map<String, Integer> positions = new HashMap<>();

    /**
     * Cash balance in base currency (e.g. USD).
     * Written by updateCash(); read by getCashBalance(), getSummary().
     */
    private double cashBalance;

    /**
     * Total realised PnL accumulated over the portfolio lifetime.
     * Written by recordPnl(); read by getSummary().
     */
    private double realisedPnl = 0.0;

    /**
     * Maximum single-position size allowed (risk limit).
     * Read by updatePosition(); written at construction and by setLimit().
     */
    private int positionLimit;

    // ─────────────────────────────────────────────────────────────────────────

    public Portfolio(String portfolioId, double initialCash, int positionLimit) {
        this.portfolioId   = portfolioId;
        this.cashBalance   = initialCash;
        this.positionLimit = positionLimit;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Position management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Apply a position delta for the given symbol.
     * Enforces position limits before accepting the change.
     *
     * @param symbol    instrument symbol
     * @param quantity  signed quantity delta (+ = bought, − = sold)
     * @param price     execution price per share
     */
    public void updatePosition(String symbol, int quantity, double price) {
        int current  = positions.getOrDefault(symbol, 0);
        int newQty   = current + quantity;

        if (Math.abs(newQty) > positionLimit) {
            throw new IllegalArgumentException(
                "Position limit exceeded for " + symbol +
                ": " + newQty + " > " + positionLimit);
        }

        positions.put(symbol, newQty);
        updateCash(-quantity * price);
    }

    /** Returns the current net position for a symbol (0 if flat). */
    public int getPosition(String symbol) {
        return positions.getOrDefault(symbol, 0);
    }

    /** True if the portfolio holds any quantity of this symbol. */
    public boolean hasPosition(String symbol) {
        return positions.getOrDefault(symbol, 0) != 0;
    }

    /** Flat all positions (sets all quantities to zero). Used in end-of-day processing. */
    public void flattenAll() {
        positions.replaceAll((sym, qty) -> 0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cash management
    // ─────────────────────────────────────────────────────────────────────────

    /** Adjust the cash balance by delta (positive = deposit, negative = withdrawal). */
    public void updateCash(double delta) {
        this.cashBalance += delta;
    }

    /** Record a realised P&L entry. */
    public void recordPnl(double pnl) {
        this.realisedPnl += pnl;
        this.cashBalance += pnl;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Valuation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mark-to-market value of the entire portfolio.
     *
     * @param prices  current mid-prices by symbol (from MarketDataFeed)
     */
    public double getValue(Map<String, Double> prices) {
        double marketValue = 0.0;
        for (Map.Entry<String, Integer> entry : positions.entrySet()) {
            Double price = prices.get(entry.getKey());
            if (price != null) {
                marketValue += entry.getValue() * price;
            }
        }
        return cashBalance + marketValue;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reporting
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns a human-readable summary string (used in logging and reports). */
    public String getSummary() {
        return String.format(
            "Portfolio[%s] cash=%.2f realisedPnl=%.2f positions=%d",
            portfolioId, cashBalance, realisedPnl, positions.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Getters / Setters
    // ─────────────────────────────────────────────────────────────────────────

    public String getPortfolioId()              { return portfolioId; }
    public double getCashBalance()              { return cashBalance; }
    public double getRealisedPnl()              { return realisedPnl; }
    public int    getPositionLimit()            { return positionLimit; }
    public void   setPositionLimit(int limit)   { this.positionLimit = limit; }
    public Map<String, Integer> getPositions()  { return Collections.unmodifiableMap(positions); }
}
