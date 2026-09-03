package com.tcs.bancs.TR;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: OrderEntity
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class OrderEntity {

    private String orderId;
    private String portfolioId;
    private String symbol;
    private String assetClass;
    private String orderSide;
    private int quantity;
    private double limitPrice;
    private double stopPrice;
    private int executedQty;
    private double cumValue;
    private String orderStatus;
    private long createdTimestamp;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public OrderEntity() {
    }

    public OrderEntity(String orderId, String portfolioId, String symbol, String assetClass, String orderSide, int quantity, double limitPrice, double stopPrice, int executedQty, double cumValue, String orderStatus, long createdTimestamp) {
        this.orderId = orderId;
        this.portfolioId = portfolioId;
        this.symbol = symbol;
        this.assetClass = assetClass;
        this.orderSide = orderSide;
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.stopPrice = stopPrice;
        this.executedQty = executedQty;
        this.cumValue = cumValue;
        this.orderStatus = orderStatus;
        this.createdTimestamp = createdTimestamp;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.orderId = id;
        this.isPersisted = true;
        this.logStateChange("Get");
        return true;
    }

    /**
     * Persists a newly created entity into underlying storage.
     */
    public synchronized boolean Create() {
        this.isPersisted = true;
        this.entityVersion = "1.0";
        this.logStateChange("Create");
        return true;
    }

    /**
     * Modifies persistent entity attributes and records mutation.
     */
    public synchronized boolean Modify(String newStatus) {
        this.entityVersion = "1.1";
        this.logStateChange("Modify");
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Business Methods (read, write, and propagate entity fields)
    // ─────────────────────────────────────────────────────────────────────────

    public synchronized void appendFill(int fillQty, double fillPrice) {
        executedQty = executedQty + fillQty; cumValue = cumValue + (fillQty * fillPrice); orderStatus = (executedQty >= quantity) ? "FILLED" : "PARTIALLY_FILLED";
        this.logStateChange("appendFill");
    }
    public synchronized void markCancelled(String reason) {
        orderStatus = "CANCELLED";
        this.logStateChange("markCancelled");
    }
    public synchronized void rejectOrder(String code) {
        orderStatus = "REJECTED";
        this.logStateChange("rejectOrder");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "OrderEntity", String.valueOf(this.orderId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getOrderId() {
        return this.orderId;
    }
    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
    public String getPortfolioId() {
        return this.portfolioId;
    }
    public void setPortfolioId(String portfolioId) {
        this.portfolioId = portfolioId;
    }
    public String getSymbol() {
        return this.symbol;
    }
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
    public String getAssetClass() {
        return this.assetClass;
    }
    public void setAssetClass(String assetClass) {
        this.assetClass = assetClass;
    }
    public String getOrderSide() {
        return this.orderSide;
    }
    public void setOrderSide(String orderSide) {
        this.orderSide = orderSide;
    }
    public int getQuantity() {
        return this.quantity;
    }
    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
    public double getLimitPrice() {
        return this.limitPrice;
    }
    public void setLimitPrice(double limitPrice) {
        this.limitPrice = limitPrice;
    }
    public double getStopPrice() {
        return this.stopPrice;
    }
    public void setStopPrice(double stopPrice) {
        this.stopPrice = stopPrice;
    }
    public int getExecutedQty() {
        return this.executedQty;
    }
    public void setExecutedQty(int executedQty) {
        this.executedQty = executedQty;
    }
    public double getCumValue() {
        return this.cumValue;
    }
    public void setCumValue(double cumValue) {
        this.cumValue = cumValue;
    }
    public String getOrderStatus() {
        return this.orderStatus;
    }
    public void setOrderStatus(String orderStatus) {
        this.orderStatus = orderStatus;
    }
    public long getCreatedTimestamp() {
        return this.createdTimestamp;
    }
    public void setCreatedTimestamp(long createdTimestamp) {
        this.createdTimestamp = createdTimestamp;
    }
}
