package com.example.trading.api;

import com.example.trading.model.OrderType;

/**
 * Immutable record representing an incoming order request.
 * Demonstrates modern Java 17+ Record support in CodeLens.
 */
public record OrderRequest(
    String orderId,
    String accountId,
    String symbol,
    int quantity,
    double limitPrice,
    OrderType orderType,
    long timestamp
) {
    public OrderRequest {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId cannot be empty");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be empty");
        }
        if (quantity == 0) {
            throw new IllegalArgumentException("quantity cannot be zero");
        }
    }

    public boolean isBuy() {
        return quantity > 0;
    }

    public boolean isSell() {
        return quantity < 0;
    }

    public double getNotionalValue() {
        return Math.abs(quantity * limitPrice);
    }
}
