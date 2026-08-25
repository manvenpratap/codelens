package com.example.trading.engine;

import com.example.trading.api.OrderRequest;
import com.example.trading.model.Portfolio;
import com.example.trading.model.Position;

/**
 * Pre-trade risk controller executing compliance, notional, concentration, and drawdown safety checks.
 */
public class RiskEngine {

    private final double maxNotionalPerOrder;
    private final double maxPositionConcentration; // max fraction of portfolio equity (e.g. 0.35 = 35%)
    private final double maxAccountLeverage;
    private int rejectedRiskCount = 0;
    private int approvedRiskCount = 0;

    public RiskEngine(double maxNotionalPerOrder, double maxPositionConcentration, double maxAccountLeverage) {
        this.maxNotionalPerOrder = maxNotionalPerOrder;
        this.maxPositionConcentration = maxPositionConcentration;
        this.maxAccountLeverage = maxAccountLeverage;
    }

    public record RiskDecision(boolean isApproved, String reason, double riskScore) {}

    public synchronized RiskDecision evaluateOrderRisk(OrderRequest request, Portfolio portfolio, MarketDataFeed feed) {
        // 1. Notional Limit Check
        RiskDecision notionalDecision = checkNotionalLimit(request);
        if (!notionalDecision.isApproved()) {
            rejectedRiskCount++;
            return notionalDecision;
        }

        // 2. Maximum Drawdown Check
        RiskDecision drawdownDecision = checkDrawdown(portfolio);
        if (!drawdownDecision.isApproved()) {
            rejectedRiskCount++;
            return drawdownDecision;
        }

        // 3. Position Concentration Check
        RiskDecision concentrationDecision = checkConcentration(request, portfolio, feed);
        if (!concentrationDecision.isApproved()) {
            rejectedRiskCount++;
            return concentrationDecision;
        }

        // 4. Account Leverage Check
        RiskDecision leverageDecision = checkLeverage(request, portfolio);
        if (!leverageDecision.isApproved()) {
            rejectedRiskCount++;
            return leverageDecision;
        }

        approvedRiskCount++;
        return new RiskDecision(true, "Order approved by pre-trade risk engine", 0.15);
    }

    private RiskDecision checkNotionalLimit(OrderRequest request) {
        double notional = request.getNotionalValue();
        if (notional > maxNotionalPerOrder) {
            return new RiskDecision(false, "Order notional " + notional + " exceeds limit " + maxNotionalPerOrder, 0.95);
        }
        return new RiskDecision(true, "Notional within limits", 0.0);
    }

    private RiskDecision checkDrawdown(Portfolio portfolio) {
        double currentDrawdown = portfolio.getCurrentDrawdownPercent();
        if (currentDrawdown >= portfolio.getMaxDrawdownLimit()) {
            return new RiskDecision(false, "Account in circuit-breaker drawdown: " + currentDrawdown + "%", 1.0);
        }
        return new RiskDecision(true, "Drawdown acceptable", currentDrawdown / 100.0);
    }

    private RiskDecision checkConcentration(OrderRequest request, Portfolio portfolio, MarketDataFeed feed) {
        double totalEquity = portfolio.getTotalEquity();
        if (totalEquity <= 0) {
            return new RiskDecision(false, "Insufficient account equity for trading", 1.0);
        }

        Position currentPos = portfolio.getPositions().get(request.symbol());
        int currentShares = currentPos != null ? currentPos.getShares() : 0;
        int projectedShares = currentShares + request.quantity();

        double midPrice = feed.getMidPrice(request.symbol());
        double projectedValue = Math.abs(projectedShares * midPrice);

        double concentrationRatio = projectedValue / totalEquity;
        if (concentrationRatio > maxPositionConcentration) {
            return new RiskDecision(false,
                String.format("Concentration %.2f%% exceeds maximum allowed %.2f%%",
                    concentrationRatio * 100, maxPositionConcentration * 100), 0.85);
        }
        return new RiskDecision(true, "Concentration acceptable", concentrationRatio);
    }

    private RiskDecision checkLeverage(OrderRequest request, Portfolio portfolio) {
        double availableMargin = portfolio.getAvailableMargin();
        double requiredMargin = request.getNotionalValue() * 0.25;

        if (requiredMargin > availableMargin) {
            return new RiskDecision(false,
                "Insufficient available margin: required=" + requiredMargin + ", available=" + availableMargin, 0.90);
        }
        return new RiskDecision(true, "Margin verified", 0.1);
    }

    public synchronized int getRejectedRiskCount() { return rejectedRiskCount; }
    public synchronized int getApprovedRiskCount() { return approvedRiskCount; }
}
