package com.example.trading.model;

import java.util.Objects;

/**
 * Mutable domain representation of an active or historical order.
 */
public class Order {

    private final String orderId;
    private final String accountId;
    private final String symbol;
    private final int quantity;
    private final double price;
    private final OrderType orderType;
    private OrderStatus status;
    private int filledQuantity;
    private double averageFillPrice;
    private long lastUpdated;

    public Order(String orderId, String accountId, String symbol, int quantity, double price, OrderType orderType) {
        this.orderId = orderId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
        this.orderType = orderType;
        this.status = OrderStatus.NEW;
        this.filledQuantity = 0;
        this.averageFillPrice = 0.0;
        this.lastUpdated = System.currentTimeMillis();
    }

    public synchronized void recordFill(int fillQty, double fillPrice) {
        double currentTotal = filledQuantity * averageFillPrice;
        filledQuantity += fillQty;
        averageFillPrice = (currentTotal + (fillQty * fillPrice)) / filledQuantity;
        status = (filledQuantity >= Math.abs(quantity)) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        lastUpdated = System.currentTimeMillis();
    }

    public synchronized void cancel() {
        if (status.canCancel()) {
            status = OrderStatus.CANCELLED;
            lastUpdated = System.currentTimeMillis();
        }
    }

    public String getOrderId() { return orderId; }
    public String getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public int getQuantity() { return quantity; }
    public double getPrice() { return price; }
    public OrderType getOrderType() { return orderType; }
    public synchronized OrderStatus getStatus() { return status; }
    public synchronized int getFilledQuantity() { return filledQuantity; }
    public synchronized double getAverageFillPrice() { return averageFillPrice; }
    public synchronized long getLastUpdated() { return lastUpdated; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Order order = (Order) o;
        return Objects.equals(orderId, order.orderId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId);
    }
}
