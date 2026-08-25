package com.example.trading.model;

/**
 * State lifecycle of an active order.
 */
public enum OrderStatus {
    NEW,
    ACCEPTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
    EXPIRED;

    public boolean isFinal() {
        return this == FILLED || this == CANCELLED || this == REJECTED || this == EXPIRED;
    }

    public boolean canCancel() {
        return this == NEW || this == ACCEPTED || this == PARTIALLY_FILLED;
    }
}
