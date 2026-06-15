package com.example.trading;

/**
 * Wires together all trading system components and runs a short demo.
 * This is the entry point for the sample project.
 *
 * Running this class demonstrates the call graph that CodeLens will index:
 *
 *   main()
 *     → bootstrap()
 *         → new MarketDataFeed()
 *         → feed.onTick() × 3
 *         → new Portfolio()
 *         → new RiskEngine()
 *         → new TradeProcessor()
 *         → new OrderService()
 *         → service.placeOrder() × 2
 *         → service.placeMarketOrder() × 1
 *         → service.printStats()
 *         → portfolio.getSummary()
 */
public class TradingSystemBootstrap {

    public static void main(String[] args) {
        System.out.println("=== CodeLens Sample: Trading System Demo ===");
        try {
            bootstrap();
        } catch (Exception e) {
            System.err.println("Demo error: " + e.getMessage());
        }
    }

    private static void bootstrap() {
        // ── 1. Market data feed ───────────────────────────────────────────────
        MarketDataFeed feed = new MarketDataFeed();
        feed.onTick("AAPL",  174.50, 174.55);
        feed.onTick("MSFT",  412.80, 412.90);
        feed.onTick("GOOGL", 175.20, 175.30);
        System.out.println("Feed initialised: " + feed.getTickCount() + " ticks");

        // ── 2. Portfolio ──────────────────────────────────────────────────────
        Portfolio portfolio = new Portfolio(
            "DEMO-001",
            100_000.00,   // $100k starting cash
            5000          // max 5000 shares per symbol
        );

        // ── 3. Risk engine ────────────────────────────────────────────────────
        RiskEngine risk = new RiskEngine(
            500_000,    // max $500k notional per order
            0.30,       // max 30% portfolio in one symbol
            -20_000     // halt if PnL < -$20k
        );

        // ── 4. Trade processor ────────────────────────────────────────────────
        TradeProcessor processor = new TradeProcessor();

        // ── 5. Order service ──────────────────────────────────────────────────
        OrderService service = new OrderService(portfolio, risk, feed, processor);

        // ── 6. Place some trades ──────────────────────────────────────────────
        try {
            TradeProcessor.TradeRecord t1 = service.placeOrder("AAPL", 100, 174.55);
            System.out.println("Filled: " + t1);

            TradeProcessor.TradeRecord t2 = service.placeOrder("MSFT", 50, 412.90);
            System.out.println("Filled: " + t2);

            TradeProcessor.TradeRecord t3 = service.placeMarketOrder("GOOGL", 25);
            System.out.println("Filled: " + t3);

        } catch (Exception e) {
            System.err.println("Order error: " + e.getMessage());
        }

        // ── 7. Print final state ──────────────────────────────────────────────
        service.printStats();
        System.out.println(portfolio.getSummary());
        System.out.printf("Total portfolio value: $%.2f%n",
            portfolio.getValue(feed.getAllMidPrices()));
    }
}
