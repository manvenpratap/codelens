package com.example.trading;

import java.util.Map;

/**
 * Pre-trade risk checks applied to every order before submission.
 *
 * Demonstrates: methods that accept Portfolio + MarketDataFeed as
 * collaborators — making it a natural hub in the call graph.
 * CodeLens will show that many paths converge on checkOrder().
 *
 * Three risk strategies (each a separate method to show call-graph depth):
 *   1. notionalLimit   — max USD value per order
 *   2. concentrationLimit — max % of portfolio in one symbol
 *   3. drawdownGuard   — halt trading if total PnL drops below threshold
 */
public class RiskEngine {

    // ── Configuration fields — read by all check methods ─────────────────────

    /** Maximum notional value allowed for a single order (USD). */
    private double maxOrderNotional;

    /** Maximum portfolio concentration in one symbol (fraction, 0–1). */
    private double maxConcentration;

    /**
     * Drawdown threshold below which all new orders are rejected.
     * E.g. -50_000 means: reject if realisedPnl < -$50,000.
     */
    private double drawdownThreshold;

    /** Count of orders rejected by risk checks since engine start. */
    private int rejectionCount = 0;

    // ─────────────────────────────────────────────────────────────────────────

    public RiskEngine(double maxOrderNotional,
                      double maxConcentration,
                      double drawdownThreshold) {
        this.maxOrderNotional  = maxOrderNotional;
        this.maxConcentration  = maxConcentration;
        this.drawdownThreshold = drawdownThreshold;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main risk gate — called by OrderService before every order
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Run all risk checks for a potential order.
     *
     * @return RiskDecision with approved/rejected status and reason
     */
    public RiskDecision checkOrder(String symbol, int quantity, double price,
                                   Portfolio portfolio, MarketDataFeed feed) {
        double notional = calculateNotional(quantity, price);

        // Check 1: notional limit
        if (!checkNotionalLimit(notional)) {
            rejectionCount++;
            return RiskDecision.reject("Notional limit exceeded: " + notional
                                     + " > " + maxOrderNotional);
        }

        // Check 2: concentration after the trade
        if (!checkConcentration(symbol, quantity, portfolio, feed)) {
            rejectionCount++;
            return RiskDecision.reject("Concentration limit would be breached for " + symbol);
        }

        // Check 3: drawdown guard
        if (!checkDrawdown(portfolio)) {
            rejectionCount++;
            return RiskDecision.reject("Drawdown threshold breached: PnL="
                                      + portfolio.getRealisedPnl());
        }

        return RiskDecision.approve();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Individual risk checks
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns false if notional value of the order exceeds the cap. */
    private boolean checkNotionalLimit(double notional) {
        return notional <= maxOrderNotional;
    }

    /**
     * Returns false if adding this trade would cause the symbol to exceed
     * maxConcentration of the total portfolio market value.
     */
    private boolean checkConcentration(String symbol, int newQty,
                                        Portfolio portfolio, MarketDataFeed feed) {
        Map<String, Double> prices = feed.getAllMidPrices();
        double totalValue = portfolio.getValue(prices);
        if (totalValue <= 0) return true; // no portfolio value yet — allow

        int existingQty    = portfolio.getPosition(symbol);
        double symbolPrice = prices.getOrDefault(symbol, 0.0);
        double exposure    = Math.abs(existingQty + newQty) * symbolPrice;

        return (exposure / totalValue) <= maxConcentration;
    }

    /** Returns false if portfolio PnL is below the drawdown threshold. */
    private boolean checkDrawdown(Portfolio portfolio) {
        return portfolio.getRealisedPnl() >= drawdownThreshold;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    private double calculateNotional(int quantity, double price) {
        return Math.abs(quantity) * price;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Getters / Setters
    // ─────────────────────────────────────────────────────────────────────────

    public int    getRejectionCount()             { return rejectionCount; }
    public double getMaxOrderNotional()           { return maxOrderNotional; }
    public void   setMaxOrderNotional(double v)   { this.maxOrderNotional = v; }
    public double getMaxConcentration()           { return maxConcentration; }
    public void   setMaxConcentration(double v)   { this.maxConcentration = v; }
    public double getDrawdownThreshold()          { return drawdownThreshold; }
    public void   setDrawdownThreshold(double v)  { this.drawdownThreshold = v; }

    // ─────────────────────────────────────────────────────────────────────────
    // Value object: result of a risk check
    // ─────────────────────────────────────────────────────────────────────────

    public static class RiskDecision {
        private final boolean approved;
        private final String  reason;

        private RiskDecision(boolean approved, String reason) {
            this.approved = approved;
            this.reason   = reason;
        }

        public static RiskDecision approve()             { return new RiskDecision(true,  ""); }
        public static RiskDecision reject(String reason) { return new RiskDecision(false, reason); }

        public boolean isApproved() { return approved; }
        public String  getReason()  { return reason; }

        @Override
        public String toString() {
            return approved ? "APPROVED" : "REJECTED: " + reason;
        }
    }
}
