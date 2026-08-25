package com.example.trading.service;

import com.example.trading.api.ExecutionReport;
import com.example.trading.api.OrderRequest;
import com.example.trading.api.OrderValidator;
import com.example.trading.audit.AuditLogger;
import com.example.trading.engine.MarketDataFeed;
import com.example.trading.engine.RiskEngine;
import com.example.trading.model.OrderType;
import com.example.trading.model.Portfolio;
import java.util.UUID;

/**
 * Top-level order management and routing service.
 * Forms the central nexus of the CodeLens call graph and impact tree.
 */
public class OrderService {

    private final PortfolioManager portfolioManager;
    private final RiskEngine riskEngine;
    private final ExecutionService executionService;
    private final MarketDataFeed marketDataFeed;
    private final NotificationService notificationService;
    private final AuditLogger auditLogger;
    private final OrderValidator orderValidator;

    private int submittedOrderCount = 0;
    private int filledOrderCount = 0;
    private int rejectedOrderCount = 0;

    public OrderService(
        PortfolioManager portfolioManager,
        RiskEngine riskEngine,
        ExecutionService executionService,
        MarketDataFeed marketDataFeed,
        NotificationService notificationService,
        AuditLogger auditLogger
    ) {
        this.portfolioManager = portfolioManager;
        this.riskEngine = riskEngine;
        this.executionService = executionService;
        this.marketDataFeed = marketDataFeed;
        this.notificationService = notificationService;
        this.auditLogger = auditLogger;
        this.orderValidator = this::validateOrderRequest;
    }

    public ExecutionReport submitLimitOrder(String accountId, String symbol, int quantity, double limitPrice) {
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
        OrderRequest request = new OrderRequest(orderId, accountId, symbol, quantity, limitPrice, OrderType.LIMIT, System.currentTimeMillis());
        return processOrder(request);
    }

    public ExecutionReport submitMarketOrder(String accountId, String symbol, int quantity) {
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
        double mid = marketDataFeed.getMidPrice(symbol);
        OrderRequest request = new OrderRequest(orderId, accountId, symbol, quantity, mid, OrderType.MARKET, System.currentTimeMillis());
        return processOrder(request);
    }

    public synchronized ExecutionReport processOrder(OrderRequest request) {
        submittedOrderCount++;
        auditLogger.logOrderAttempt(request);

        // 1. Basic validation
        orderValidator.validate(request);

        // 2. Fetch target portfolio
        Portfolio portfolio = portfolioManager.getPortfolio(request.accountId());
        if (portfolio == null) {
            rejectedOrderCount++;
            auditLogger.logRejection(request.orderId(), "Account not found: " + request.accountId());
            throw new IllegalArgumentException("Unknown account: " + request.accountId());
        }

        // 3. Pre-trade risk assessment
        RiskEngine.RiskDecision decision = riskEngine.evaluateOrderRisk(request, portfolio, marketDataFeed);
        if (!decision.isApproved()) {
            rejectedOrderCount++;
            notificationService.notifyRiskRejection(request.orderId(), decision.reason());
            auditLogger.logRejection(request.orderId(), decision.reason());
            throw new IllegalStateException("Order rejected by Risk Engine: " + decision.reason());
        }

        // 4. Execution dispatch
        ExecutionReport report = executionService.execute(request, portfolio);
        filledOrderCount++;

        // 5. Post-trade audit and alert
        auditLogger.logExecution(report);
        notificationService.notifyExecution(report);

        return report;
    }

    private void validateOrderRequest(OrderRequest req) {
        if (req.symbol() == null || req.symbol().isBlank()) {
            throw new IllegalArgumentException("Symbol must be specified");
        }
        if (req.quantity() == 0) {
            throw new IllegalArgumentException("Order quantity cannot be zero");
        }
        if (req.limitPrice() < 0) {
            throw new IllegalArgumentException("Price cannot be negative");
        }
    }

    public synchronized int getSubmittedOrderCount() { return submittedOrderCount; }
    public synchronized int getFilledOrderCount() { return filledOrderCount; }
    public synchronized int getRejectedOrderCount() { return rejectedOrderCount; }
}
