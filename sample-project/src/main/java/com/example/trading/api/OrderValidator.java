package com.example.trading.api;

/**
 * Functional validation interface for incoming orders.
 */
@FunctionalInterface
public interface OrderValidator {

    /**
     * Validate an incoming order request.
     * @param request the order to inspect
     * @throws IllegalArgumentException if validation fails
     */
    void validate(OrderRequest request);
}
