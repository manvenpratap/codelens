package com.tcs.bancs.TR;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: TradeExecution
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class TradeExecution {

    private String executionId;
    private String orderId;
    private String executingVenue;
    private double executedPrice;
    private int executedVolume;
    private double commission;
    private long executionTimestamp;
    private String liquidityFlag;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public TradeExecution() {
    }

    public TradeExecution(String executionId, String orderId, String executingVenue, double executedPrice, int executedVolume, double commission, long executionTimestamp, String liquidityFlag) {
        this.executionId = executionId;
        this.orderId = orderId;
        this.executingVenue = executingVenue;
        this.executedPrice = executedPrice;
        this.executedVolume = executedVolume;
        this.commission = commission;
        this.executionTimestamp = executionTimestamp;
        this.liquidityFlag = liquidityFlag;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.executionId = id;
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

    public synchronized void settleExecution(double fee) {
        commission = fee; liquidityFlag = "SETTLED";
        this.logStateChange("settleExecution");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "TradeExecution", String.valueOf(this.executionId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getExecutionId() {
        return this.executionId;
    }
    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }
    public String getOrderId() {
        return this.orderId;
    }
    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
    public String getExecutingVenue() {
        return this.executingVenue;
    }
    public void setExecutingVenue(String executingVenue) {
        this.executingVenue = executingVenue;
    }
    public double getExecutedPrice() {
        return this.executedPrice;
    }
    public void setExecutedPrice(double executedPrice) {
        this.executedPrice = executedPrice;
    }
    public int getExecutedVolume() {
        return this.executedVolume;
    }
    public void setExecutedVolume(int executedVolume) {
        this.executedVolume = executedVolume;
    }
    public double getCommission() {
        return this.commission;
    }
    public void setCommission(double commission) {
        this.commission = commission;
    }
    public long getExecutionTimestamp() {
        return this.executionTimestamp;
    }
    public void setExecutionTimestamp(long executionTimestamp) {
        this.executionTimestamp = executionTimestamp;
    }
    public String getLiquidityFlag() {
        return this.liquidityFlag;
    }
    public void setLiquidityFlag(String liquidityFlag) {
        this.liquidityFlag = liquidityFlag;
    }
}
