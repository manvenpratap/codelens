package com.example.trading;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Processes confirmed trade executions: updates the portfolio, records PnL,
 * and maintains an immutable trade blotter.
 *
 * CodeLens interest:
 *   - process() is a hub method called by OrderService
 *   - blotter field is read by multiple reporting methods
 *   - realisedPnl propagates through recordTrade() → portfolio.recordPnl()
 */
public class TradeProcessor {

    /** Immutable trade records for all processed executions. */
    private final List<TradeRecord> blotter = new ArrayList<>();

    /** Running tally of realised PnL across all processed trades. */
    private double realisedPnl = 0.0;

    /**
     * Flag set to true when batch-processing is active.
     * While true, individual process() calls are queued rather than applied.
     */
    private boolean batchMode = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Core processing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Process a single trade execution against the given portfolio.
     *
     * @param symbol    traded symbol
     * @param quantity  signed quantity (+ = buy fill, − = sell fill)
     * @param fillPrice actual execution price
     * @param portfolio portfolio to update
     */
    public TradeRecord process(String symbol, int quantity, double fillPrice,
                               Portfolio portfolio) {
        validateTrade(symbol, quantity, fillPrice);

        // Update portfolio position and cash
        portfolio.updatePosition(symbol, quantity, fillPrice);

        // Calculate and record PnL for closing trades
        double pnl = computePnl(symbol, quantity, fillPrice, portfolio);
        if (pnl != 0) {
            recordTrade(pnl, portfolio);
        }

        // Create and store blotter record
        TradeRecord record = new TradeRecord(
            UUID.randomUUID().toString(),
            symbol, quantity, fillPrice, pnl,
            Instant.now().toEpochMilli());

        blotter.add(record);
        return record;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PnL calculation (simplified FIFO for demonstration)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compute realised PnL for a closing or partial-close trade.
     * Returns 0 for opening trades.
     */
    private double computePnl(String symbol, int quantity,
                               double fillPrice, Portfolio portfolio) {
        // Simplified: PnL = 0 for net new positions
        int positionBefore = portfolio.getPosition(symbol) - quantity;
        boolean isClosing  = (positionBefore > 0 && quantity < 0)
                          || (positionBefore < 0 && quantity > 0);
        if (!isClosing) return 0.0;

        // For demonstration, apply a simple cost-basis assumption
        // (real implementation would use FIFO lot tracking)
        return -quantity * fillPrice * 0.002; // stub: 0.2% PnL on close
    }

    /** Post PnL to the portfolio and update internal running total. */
    private void recordTrade(double pnl, Portfolio portfolio) {
        this.realisedPnl += pnl;
        portfolio.recordPnl(pnl);
    }

    /** Basic validation of trade parameters. */
    private void validateTrade(String symbol, int quantity, double price) {
        if (symbol == null || symbol.isBlank())
            throw new IllegalArgumentException("Trade symbol cannot be blank");
        if (quantity == 0)
            throw new IllegalArgumentException("Trade quantity cannot be zero");
        if (price <= 0)
            throw new IllegalArgumentException("Fill price must be positive, got: " + price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Blotter queries
    // ─────────────────────────────────────────────────────────────────────────

    /** Read-only view of the full trade blotter. */
    public List<TradeRecord> getBlotter() {
        return Collections.unmodifiableList(blotter);
    }

    /** Filter blotter to a specific symbol. */
    public List<TradeRecord> getBlotterForSymbol(String symbol) {
        List<TradeRecord> result = new ArrayList<>();
        for (TradeRecord r : blotter) {
            if (symbol.equals(r.symbol())) result.add(r);
        }
        return result;
    }

    /** Total number of processed trades. */
    public int getTradeCount()   { return blotter.size(); }
    public double getRealisedPnl() { return realisedPnl; }
    public boolean isBatchMode()   { return batchMode; }
    public void    setBatchMode(boolean b) { this.batchMode = b; }

    // ─────────────────────────────────────────────────────────────────────────
    // Trade blotter record (Java 16+ record-style — written as a class for
    // Java 11 compatibility)
    // ─────────────────────────────────────────────────────────────────────────

    public static final class TradeRecord {
        private final String tradeId;
        private final String symbol;
        private final int    quantity;
        private final double fillPrice;
        private final double realisedPnl;
        private final long   timestamp;

        public TradeRecord(String tradeId, String symbol, int quantity,
                           double fillPrice, double realisedPnl, long timestamp) {
            this.tradeId     = tradeId;
            this.symbol      = symbol;
            this.quantity    = quantity;
            this.fillPrice   = fillPrice;
            this.realisedPnl = realisedPnl;
            this.timestamp   = timestamp;
        }

        public String tradeId()      { return tradeId; }
        public String symbol()       { return symbol; }
        public int    quantity()     { return quantity; }
        public double fillPrice()    { return fillPrice; }
        public double realisedPnl()  { return realisedPnl; }
        public long   timestamp()    { return timestamp; }

        @Override
        public String toString() {
            return String.format("Trade[%s %s qty=%d @%.4f pnl=%.2f]",
                tradeId, symbol, quantity, fillPrice, realisedPnl);
        }
    }
}
