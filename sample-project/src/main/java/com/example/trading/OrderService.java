package com.example.trading;

/**
 * Top-level order entry service — the main call-graph hub in this sample.
 *
 * Flow:  placeOrder()
 *           → validateOrder()
 *           → riskEngine.checkOrder()
 *               → riskEngine.checkNotionalLimit()   [private, via checkOrder]
 *               → riskEngine.checkConcentration()   [private]
 *               → riskEngine.checkDrawdown()         [private]
 *           → marketDataFeed.getMidPrice()
 *           → tradeProcessor.process()
 *               → portfolio.updatePosition()
 *               → portfolio.recordPnl()
 *
 * This chain gives CodeLens a rich multi-level call graph to visualise.
 * Selecting placeOrder() in the Graph tab should show ~10 reachable nodes.
 *
 * Also note:
 *   - 'orderCount' field: written by placeOrder, read by getOrderCount
 *   - 'rejectedCount' field: written by placeOrder on rejection, read by stats
 *   - Inconsistency detector should flag: placeOrder vs placeMarketOrder
 *     (same concept, divergent parameter signatures)
 */
public class OrderService {

    private final Portfolio       portfolio;
    private final RiskEngine      riskEngine;
    private final MarketDataFeed  marketDataFeed;
    private final TradeProcessor  tradeProcessor;

    /** Total orders submitted (including rejected). */
    private int orderCount    = 0;

    /** Orders rejected by risk checks. */
    private int rejectedCount = 0;

    /** Orders successfully executed. */
    private int filledCount   = 0;

    // ─────────────────────────────────────────────────────────────────────────

    public OrderService(Portfolio portfolio,
                        RiskEngine riskEngine,
                        MarketDataFeed marketDataFeed,
                        TradeProcessor tradeProcessor) {
        this.portfolio      = portfolio;
        this.riskEngine     = riskEngine;
        this.marketDataFeed = marketDataFeed;
        this.tradeProcessor = tradeProcessor;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Order submission
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Submit a limit order at a specified price.
     * The order is subject to pre-trade risk checks before execution.
     *
     * @param symbol    instrument symbol (e.g. "AAPL", "MSFT")
     * @param quantity  signed quantity: positive = buy, negative = sell
     * @param limitPrice maximum acceptable fill price for buys (minimum for sells)
     * @return the filled TradeRecord, or throws on rejection/error
     */
    public TradeProcessor.TradeRecord placeOrder(String symbol, int quantity,
                                                  double limitPrice) {
        orderCount++;
        validateOrder(symbol, quantity, limitPrice);

        // Fetch current market price to check against limit
        double midPrice = marketDataFeed.getMidPrice(symbol);
        if (midPrice <= 0) {
            throw new IllegalStateException("No market data for symbol: " + symbol);
        }

        // Pre-trade risk check
        RiskEngine.RiskDecision decision =
            riskEngine.checkOrder(symbol, quantity, limitPrice, portfolio, marketDataFeed);

        if (!decision.isApproved()) {
            rejectedCount++;
            throw new IllegalStateException("Order rejected by risk: " + decision.getReason());
        }

        // Execute at limit price (simplified: no partial fills)
        TradeProcessor.TradeRecord trade =
            tradeProcessor.process(symbol, quantity, limitPrice, portfolio);

        filledCount++;
        return trade;
    }

    /**
     * Submit a market order — fills at the current mid-price.
     * Deliberately has a slightly different structure to placeOrder() so that
     * CodeLens inconsistency detection can flag the divergence.
     */
    public TradeProcessor.TradeRecord placeMarketOrder(String symbol, int quantity) {
        orderCount++;

        // Note: market orders skip limit-price validation — intentional divergence
        // from placeOrder() which the inconsistency detector should surface.
        double midPrice = marketDataFeed.getMidPrice(symbol);
        if (midPrice <= 0) {
            throw new IllegalStateException("Cannot place market order: no quote for " + symbol);
        }

        RiskEngine.RiskDecision decision =
            riskEngine.checkOrder(symbol, quantity, midPrice, portfolio, marketDataFeed);

        if (!decision.isApproved()) {
            rejectedCount++;
            throw new IllegalStateException("Market order rejected: " + decision.getReason());
        }

        TradeProcessor.TradeRecord trade =
            tradeProcessor.process(symbol, quantity, midPrice, portfolio);

        filledCount++;
        return trade;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Validation
    // ─────────────────────────────────────────────────────────────────────────

    /** Basic sanity checks applied to every incoming order. */
    private void validateOrder(String symbol, int quantity, double price) {
        if (symbol == null || symbol.isBlank())
            throw new IllegalArgumentException("Symbol must not be blank");
        if (quantity == 0)
            throw new IllegalArgumentException("Order quantity cannot be zero");
        if (price <= 0)
            throw new IllegalArgumentException("Limit price must be positive");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Statistics
    // ─────────────────────────────────────────────────────────────────────────

    public int getOrderCount()    { return orderCount; }
    public int getRejectedCount() { return rejectedCount; }
    public int getFilledCount()   { return filledCount; }

    /** Print a summary to stdout (used in integration tests). */
    public void printStats() {
        System.out.printf(
            "OrderService stats: total=%d filled=%d rejected=%d%n",
            orderCount, filledCount, rejectedCount);
        System.out.println(portfolio.getSummary());
    }
}
