package com.tcs.bancs.RK;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: RiskExposure
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class RiskExposure {

    private String exposureId;
    private String counterpartyId;
    private String exposureType;
    private double currentExposure;
    private double peakExposure;
    private double potentialFutureExposure;
    private double collateralHeld;
    private double netExposure;
    private double riskWeight;
    private String calculatedDate;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public RiskExposure() {
    }

    public RiskExposure(String exposureId, String counterpartyId, String exposureType, double currentExposure, double peakExposure, double potentialFutureExposure, double collateralHeld, double netExposure, double riskWeight, String calculatedDate) {
        this.exposureId = exposureId;
        this.counterpartyId = counterpartyId;
        this.exposureType = exposureType;
        this.currentExposure = currentExposure;
        this.peakExposure = peakExposure;
        this.potentialFutureExposure = potentialFutureExposure;
        this.collateralHeld = collateralHeld;
        this.netExposure = netExposure;
        this.riskWeight = riskWeight;
        this.calculatedDate = calculatedDate;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.exposureId = id;
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

    public synchronized void updateExposure(double newVal) {
        currentExposure = newVal; peakExposure = Math.max(peakExposure, newVal); netExposure = Math.max(0.0, currentExposure - collateralHeld);
        this.logStateChange("updateExposure");
    }
    public synchronized void recalculatePFE(double confidenceMultiplier) {
        potentialFutureExposure = currentExposure * confidenceMultiplier;
        this.logStateChange("recalculatePFE");
    }
    public synchronized void applyHaircut(double haircutPct) {
        collateralHeld = collateralHeld * (1.0 - haircutPct); netExposure = Math.max(0.0, currentExposure - collateralHeld);
        this.logStateChange("applyHaircut");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "RiskExposure", String.valueOf(this.exposureId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getExposureId() {
        return this.exposureId;
    }
    public void setExposureId(String exposureId) {
        this.exposureId = exposureId;
    }
    public String getCounterpartyId() {
        return this.counterpartyId;
    }
    public void setCounterpartyId(String counterpartyId) {
        this.counterpartyId = counterpartyId;
    }
    public String getExposureType() {
        return this.exposureType;
    }
    public void setExposureType(String exposureType) {
        this.exposureType = exposureType;
    }
    public double getCurrentExposure() {
        return this.currentExposure;
    }
    public void setCurrentExposure(double currentExposure) {
        this.currentExposure = currentExposure;
    }
    public double getPeakExposure() {
        return this.peakExposure;
    }
    public void setPeakExposure(double peakExposure) {
        this.peakExposure = peakExposure;
    }
    public double getPotentialFutureExposure() {
        return this.potentialFutureExposure;
    }
    public void setPotentialFutureExposure(double potentialFutureExposure) {
        this.potentialFutureExposure = potentialFutureExposure;
    }
    public double getCollateralHeld() {
        return this.collateralHeld;
    }
    public void setCollateralHeld(double collateralHeld) {
        this.collateralHeld = collateralHeld;
    }
    public double getNetExposure() {
        return this.netExposure;
    }
    public void setNetExposure(double netExposure) {
        this.netExposure = netExposure;
    }
    public double getRiskWeight() {
        return this.riskWeight;
    }
    public void setRiskWeight(double riskWeight) {
        this.riskWeight = riskWeight;
    }
    public String getCalculatedDate() {
        return this.calculatedDate;
    }
    public void setCalculatedDate(String calculatedDate) {
        this.calculatedDate = calculatedDate;
    }
}
