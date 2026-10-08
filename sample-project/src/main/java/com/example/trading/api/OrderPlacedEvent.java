package com.example.trading.api;

/**
 * Domain event published when an order is submitted.
 */
public record OrderPlacedEvent(String orderId, String symbol, int quantity) {}
