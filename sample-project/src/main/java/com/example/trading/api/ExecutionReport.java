package com.example.trading.api;

import com.example.trading.model.OrderStatus;

/**
 * Immutable execution confirmation report emitted after order processing.
 */
public record ExecutionReport(
    String execId,
    String orderId,
    String accountId,
    String symbol,
    int filledQuantity,
    double averageFillPrice,
    double fee,
    OrderStatus status,
    long timestamp
) {
    public boolean isTerminal() {
        return status == OrderStatus.FILLED || status == OrderStatus.CANCELLED || status == OrderStatus.REJECTED;
    }

    public double getExecutedValue() {
        return filledQuantity * averageFillPrice;
    }
}
