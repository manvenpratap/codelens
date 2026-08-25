package com.example.trading.model;

/**
 * Supported financial order execution types.
 * Demonstrates Enum representation in CodeLens.
 */
public enum OrderType {
    LIMIT("Limit Order", true),
    MARKET("Market Order", false),
    STOP_LOSS("Stop Loss Order", true),
    TRAILING_STOP("Trailing Stop Order", false),
    ICEBERG("Iceberg Hidden Size Order", true);

    private final String description;
    private final boolean requiresPrice;

    OrderType(String description, boolean requiresPrice) {
        this.description = description;
        this.requiresPrice = requiresPrice;
    }

    public String getDescription() {
        return description;
    }

    public boolean isRequiresPrice() {
        return requiresPrice;
    }
}
