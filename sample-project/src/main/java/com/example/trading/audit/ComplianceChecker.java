package com.example.trading.audit;

import com.example.trading.api.OrderRequest;
import java.util.List;

/**
 * Real-time trade surveillance for spoofing, layering, and wash sales.
 */
public class ComplianceChecker {

    public boolean detectWashSaleRisk(OrderRequest newOrder, List<OrderRequest> recentOrders) {
        for (OrderRequest prior : recentOrders) {
            if (prior.symbol().equals(newOrder.symbol()) &&
                prior.accountId().equals(newOrder.accountId()) &&
                ((prior.isBuy() && newOrder.isSell()) || (prior.isSell() && newOrder.isBuy()))) {
                long timeDiff = Math.abs(newOrder.timestamp() - prior.timestamp());
                if (timeDiff < 60_000) { // Same symbol traded in opposite directions within 1 minute
                    return true;
                }
            }
        }
        return false;
    }
}
