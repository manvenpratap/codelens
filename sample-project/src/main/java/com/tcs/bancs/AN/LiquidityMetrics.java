package com.tcs.bancs.AN;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: LiquidityMetrics
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class LiquidityMetrics {

    private String metricId;
    private String calculationDate;
    private double highQualityLiquidAssets;
    private double totalNetCashOutflow30d;
    private double lcrRatio;
    private double availableStableFunding;
    private double nsfrRatio;
    private String complianceStatus;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public LiquidityMetrics() {
    }

    public LiquidityMetrics(String metricId, String calculationDate, double highQualityLiquidAssets, double totalNetCashOutflow30d, double lcrRatio, double availableStableFunding, double nsfrRatio, String complianceStatus) {
        this.metricId = metricId;
        this.calculationDate = calculationDate;
        this.highQualityLiquidAssets = highQualityLiquidAssets;
        this.totalNetCashOutflow30d = totalNetCashOutflow30d;
        this.lcrRatio = lcrRatio;
        this.availableStableFunding = availableStableFunding;
        this.nsfrRatio = nsfrRatio;
        this.complianceStatus = complianceStatus;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.metricId = id;
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

    public synchronized void computeLCR() {
        lcrRatio = (totalNetCashOutflow30d > 0) ? (highQualityLiquidAssets / totalNetCashOutflow30d) * 100.0 : 100.0; complianceStatus = (lcrRatio >= 100.0) ? "COMPLIANT" : "BREACH";
        this.logStateChange("computeLCR");
    }
    public synchronized void validateRatios() {
        if (nsfrRatio >= 100.0 && lcrRatio >= 100.0) complianceStatus = "COMPLIANT"; else complianceStatus = "DEFICIT";
        this.logStateChange("validateRatios");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "LiquidityMetrics", String.valueOf(this.metricId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getMetricId() {
        return this.metricId;
    }
    public void setMetricId(String metricId) {
        this.metricId = metricId;
    }
    public String getCalculationDate() {
        return this.calculationDate;
    }
    public void setCalculationDate(String calculationDate) {
        this.calculationDate = calculationDate;
    }
    public double getHighQualityLiquidAssets() {
        return this.highQualityLiquidAssets;
    }
    public void setHighQualityLiquidAssets(double highQualityLiquidAssets) {
        this.highQualityLiquidAssets = highQualityLiquidAssets;
    }
    public double getTotalNetCashOutflow30d() {
        return this.totalNetCashOutflow30d;
    }
    public void setTotalNetCashOutflow30d(double totalNetCashOutflow30d) {
        this.totalNetCashOutflow30d = totalNetCashOutflow30d;
    }
    public double getLcrRatio() {
        return this.lcrRatio;
    }
    public void setLcrRatio(double lcrRatio) {
        this.lcrRatio = lcrRatio;
    }
    public double getAvailableStableFunding() {
        return this.availableStableFunding;
    }
    public void setAvailableStableFunding(double availableStableFunding) {
        this.availableStableFunding = availableStableFunding;
    }
    public double getNsfrRatio() {
        return this.nsfrRatio;
    }
    public void setNsfrRatio(double nsfrRatio) {
        this.nsfrRatio = nsfrRatio;
    }
    public String getComplianceStatus() {
        return this.complianceStatus;
    }
    public void setComplianceStatus(String complianceStatus) {
        this.complianceStatus = complianceStatus;
    }
}
