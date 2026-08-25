package com.example.trading;

import com.example.trading.api.ExecutionReport;
import com.example.trading.audit.AuditLogger;
import com.example.trading.engine.MarketDataFeed;
import com.example.trading.engine.MatchingEngine;
import com.example.trading.engine.PricingEngine;
import com.example.trading.engine.RiskEngine;
import com.example.trading.model.Portfolio;
import com.example.trading.service.ExecutionService;
import com.example.trading.service.NotificationService;
import com.example.trading.service.OrderService;
import com.example.trading.service.PortfolioManager;

/**
 * System bootstrap orchestrator and simulation driver.
 * Main entry point for the sample electronic trading platform.
 */
public class TradingSystemBootstrap {

    private final MarketDataFeed marketDataFeed;
    private final PricingEngine pricingEngine;
    private final MatchingEngine matchingEngine;
    private final RiskEngine riskEngine;
    private final PortfolioManager portfolioManager;
    private final ExecutionService executionService;
    private final NotificationService notificationService;
    private final AuditLogger auditLogger;
    private final OrderService orderService;

    public TradingSystemBootstrap() {
        System.out.println("Initializing CodeLens Quantitative Trading Platform...");

        // 1. Core Market & Pricing Engines
        this.marketDataFeed = new MarketDataFeed();
        this.pricingEngine = new PricingEngine();
        this.matchingEngine = new MatchingEngine(marketDataFeed, pricingEngine);

        // 2. Risk & Surveillance Engine: $500k max notional, 35% concentration limit, 4x leverage
        this.riskEngine = new RiskEngine(500_000.0, 0.35, 4.0);

        // 3. Portfolios & Account Balances
        this.portfolioManager = new PortfolioManager(marketDataFeed);
        Portfolio mainAccount = portfolioManager.registerAccount("ACC-QUANT-001", 1_000_000.0, 15.0);
        portfolioManager.registerAccount("ACC-RETAIL-002", 50_000.0, 20.0);

        // 4. Execution & Notification Services
        this.executionService = new ExecutionService(matchingEngine);
        this.notificationService = new NotificationService();
        this.auditLogger = new AuditLogger();

        // 5. Order Management Service Nexus
        this.orderService = new OrderService(
            portfolioManager,
            riskEngine,
            executionService,
            marketDataFeed,
            notificationService,
            auditLogger
        );
    }

    public void startSimulation() {
        System.out.println("\n--- Executing Algorithmic Trading Simulation ---");

        // Submit initial order batch
        try {
            ExecutionReport r1 = orderService.submitLimitOrder("ACC-QUANT-001", "AAPL", 100, 185.00);
            ExecutionReport r2 = orderService.submitLimitOrder("ACC-QUANT-001", "NVDA", 250, 124.50);
            ExecutionReport r3 = orderService.submitMarketOrder("ACC-QUANT-001", "MSFT", 50);

            System.out.printf("Simulation complete. Orders submitted: %d, Executions: %d%n",
                orderService.getSubmittedOrderCount(), orderService.getFilledOrderCount());

            portfolioManager.updateAllValuations();
            Portfolio p = portfolioManager.getPortfolio("ACC-QUANT-001");
            System.out.printf("Portfolio ACC-QUANT-001 Total Equity: $%.2f | Margin Used: $%.2f%n",
                p.getTotalEquity(), p.getMarginUsed());

        } catch (Exception e) {
            System.err.println("Simulation error: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        TradingSystemBootstrap bootstrap = new TradingSystemBootstrap();
        bootstrap.startSimulation();
    }
}
