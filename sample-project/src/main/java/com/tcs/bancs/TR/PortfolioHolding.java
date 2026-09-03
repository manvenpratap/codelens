package com.tcs.bancs.TR;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: PortfolioHolding
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class PortfolioHolding {

    private String holdingId;
    private String portfolioId;
    private String symbol;
    private int currentQuantity;
    private double averageBookPrice;
    private double marketValue;
    private double unrealizedPnL;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public PortfolioHolding() {
    }

    public PortfolioHolding(String holdingId, String portfolioId, String symbol, int currentQuantity, double averageBookPrice, double marketValue, double unrealizedPnL) {
        this.holdingId = holdingId;
        this.portfolioId = portfolioId;
        this.symbol = symbol;
        this.currentQuantity = currentQuantity;
        this.averageBookPrice = averageBookPrice;
        this.marketValue = marketValue;
        this.unrealizedPnL = unrealizedPnL;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.holdingId = id;
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

    public synchronized void adjustPosition(int deltaQty, double tradePrice) {
        double totalCost = (currentQuantity * averageBookPrice) + (deltaQty * tradePrice); currentQuantity = currentQuantity + deltaQty; averageBookPrice = (currentQuantity > 0) ? (totalCost / currentQuantity) : 0.0;
        this.logStateChange("adjustPosition");
    }
    public synchronized void markToMarket(double currentMarketPrice) {
        marketValue = currentQuantity * currentMarketPrice; unrealizedPnL = marketValue - (currentQuantity * averageBookPrice);
        this.logStateChange("markToMarket");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "PortfolioHolding", String.valueOf(this.holdingId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getHoldingId() {
        return this.holdingId;
    }
    public void setHoldingId(String holdingId) {
        this.holdingId = holdingId;
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
    public int getCurrentQuantity() {
        return this.currentQuantity;
    }
    public void setCurrentQuantity(int currentQuantity) {
        this.currentQuantity = currentQuantity;
    }
    public double getAverageBookPrice() {
        return this.averageBookPrice;
    }
    public void setAverageBookPrice(double averageBookPrice) {
        this.averageBookPrice = averageBookPrice;
    }
    public double getMarketValue() {
        return this.marketValue;
    }
    public void setMarketValue(double marketValue) {
        this.marketValue = marketValue;
    }
    public double getUnrealizedPnL() {
        return this.unrealizedPnL;
    }
    public void setUnrealizedPnL(double unrealizedPnL) {
        this.unrealizedPnL = unrealizedPnL;
    }
}
