package com.tcs.bancs.RK;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: VaRCalculationResult
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class VaRCalculationResult {

    private String varId;
    private String portfolioId;
    private double confidenceInterval;
    private int timeHorizonDays;
    private double historicalVaR;
    private double parametricVaR;
    private double monteCarloVaR;
    private long calculationTimestamp;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public VaRCalculationResult() {
    }

    public VaRCalculationResult(String varId, String portfolioId, double confidenceInterval, int timeHorizonDays, double historicalVaR, double parametricVaR, double monteCarloVaR, long calculationTimestamp) {
        this.varId = varId;
        this.portfolioId = portfolioId;
        this.confidenceInterval = confidenceInterval;
        this.timeHorizonDays = timeHorizonDays;
        this.historicalVaR = historicalVaR;
        this.parametricVaR = parametricVaR;
        this.monteCarloVaR = monteCarloVaR;
        this.calculationTimestamp = calculationTimestamp;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.varId = id;
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

    public synchronized void backtest(double actualLoss) {
        if (actualLoss > historicalVaR) calculationTimestamp = System.currentTimeMillis();
        this.logStateChange("backtest");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "VaRCalculationResult", String.valueOf(this.varId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getVarId() {
        return this.varId;
    }
    public void setVarId(String varId) {
        this.varId = varId;
    }
    public String getPortfolioId() {
        return this.portfolioId;
    }
    public void setPortfolioId(String portfolioId) {
        this.portfolioId = portfolioId;
    }
    public double getConfidenceInterval() {
        return this.confidenceInterval;
    }
    public void setConfidenceInterval(double confidenceInterval) {
        this.confidenceInterval = confidenceInterval;
    }
    public int getTimeHorizonDays() {
        return this.timeHorizonDays;
    }
    public void setTimeHorizonDays(int timeHorizonDays) {
        this.timeHorizonDays = timeHorizonDays;
    }
    public double getHistoricalVaR() {
        return this.historicalVaR;
    }
    public void setHistoricalVaR(double historicalVaR) {
        this.historicalVaR = historicalVaR;
    }
    public double getParametricVaR() {
        return this.parametricVaR;
    }
    public void setParametricVaR(double parametricVaR) {
        this.parametricVaR = parametricVaR;
    }
    public double getMonteCarloVaR() {
        return this.monteCarloVaR;
    }
    public void setMonteCarloVaR(double monteCarloVaR) {
        this.monteCarloVaR = monteCarloVaR;
    }
    public long getCalculationTimestamp() {
        return this.calculationTimestamp;
    }
    public void setCalculationTimestamp(long calculationTimestamp) {
        this.calculationTimestamp = calculationTimestamp;
    }
}
