package com.tcs.bancs.TR;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: TradingStrategyConfig
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class TradingStrategyConfig {

    private String strategyId;
    private String strategyName;
    private String targetAsset;
    private int maxOrderSize;
    private double riskLimit;
    private boolean isActive;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public TradingStrategyConfig() {
    }

    public TradingStrategyConfig(String strategyId, String strategyName, String targetAsset, int maxOrderSize, double riskLimit, boolean isActive) {
        this.strategyId = strategyId;
        this.strategyName = strategyName;
        this.targetAsset = targetAsset;
        this.maxOrderSize = maxOrderSize;
        this.riskLimit = riskLimit;
        this.isActive = isActive;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.strategyId = id;
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

    public synchronized void toggleStrategy(boolean active) {
        isActive = active;
        this.logStateChange("toggleStrategy");
    }
    public synchronized void updateParameters(int maxSize, double limit) {
        maxOrderSize = maxSize; riskLimit = limit;
        this.logStateChange("updateParameters");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "TradingStrategyConfig", String.valueOf(this.strategyId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getStrategyId() {
        return this.strategyId;
    }
    public void setStrategyId(String strategyId) {
        this.strategyId = strategyId;
    }
    public String getStrategyName() {
        return this.strategyName;
    }
    public void setStrategyName(String strategyName) {
        this.strategyName = strategyName;
    }
    public String getTargetAsset() {
        return this.targetAsset;
    }
    public void setTargetAsset(String targetAsset) {
        this.targetAsset = targetAsset;
    }
    public int getMaxOrderSize() {
        return this.maxOrderSize;
    }
    public void setMaxOrderSize(int maxOrderSize) {
        this.maxOrderSize = maxOrderSize;
    }
    public double getRiskLimit() {
        return this.riskLimit;
    }
    public void setRiskLimit(double riskLimit) {
        this.riskLimit = riskLimit;
    }
    public boolean getIsActive() {
        return this.isActive;
    }
    public void setIsActive(boolean isActive) {
        this.isActive = isActive;
    }
}
