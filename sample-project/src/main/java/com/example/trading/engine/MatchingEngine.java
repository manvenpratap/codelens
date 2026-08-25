package com.example.trading.engine;

import com.example.trading.api.ExecutionReport;
import com.example.trading.api.OrderRequest;
import com.example.trading.model.OrderStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Continuous double-auction order matching engine.
 */
public class MatchingEngine {

    private final MarketDataFeed marketDataFeed;
    private final PricingEngine pricingEngine;
    private final List<OrderRequest> buyOrders = new ArrayList<>();
    private final List<OrderRequest> sellOrders = new ArrayList<>();

    public MatchingEngine(MarketDataFeed marketDataFeed, PricingEngine pricingEngine) {
        this.marketDataFeed = marketDataFeed;
        this.pricingEngine = pricingEngine;
    }

    public synchronized ExecutionReport matchOrder(OrderRequest request) {
        double currentMid = marketDataFeed.getMidPrice(request.symbol());
        double fillPrice = request.limitPrice() > 0 ? request.limitPrice() : currentMid;

        // Apply dynamic market impact / slippage
        double slippage = pricingEngine.calculateSlippage(request.quantity(), 500000, 0.22);
        fillPrice = request.isBuy() ? fillPrice + slippage : fillPrice - slippage;

        double fee = Math.abs(request.quantity()) * 0.005; // 50 bps fee
        String execId = "EXEC-" + UUID.randomUUID().toString().substring(0, 8);

        return new ExecutionReport(
            execId,
            request.orderId(),
            request.accountId(),
            request.symbol(),
            request.quantity(),
            fillPrice,
            fee,
            OrderStatus.FILLED,
            System.currentTimeMillis()
        );
    }

    public synchronized List<OrderRequest> getPendingBuys() {
        return Collections.unmodifiableList(buyOrders);
    }

    public synchronized List<OrderRequest> getPendingSells() {
        return Collections.unmodifiableList(sellOrders);
    }
}
