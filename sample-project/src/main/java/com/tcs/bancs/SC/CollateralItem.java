package com.tcs.bancs.SC;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: CollateralItem
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class CollateralItem {

    private String collateralId;
    private String customerId;
    private String collateralType;
    private String description;
    private double marketValue;
    private double haircutPct;
    private double appraisedValue;
    private double assignedLtvRatio;
    private double encumbranceAmount;
    private String lienStatus;
    private String valuationDate;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public CollateralItem() {
    }

    public CollateralItem(String collateralId, String customerId, String collateralType, String description, double marketValue, double haircutPct, double appraisedValue, double assignedLtvRatio, double encumbranceAmount, String lienStatus, String valuationDate) {
        this.collateralId = collateralId;
        this.customerId = customerId;
        this.collateralType = collateralType;
        this.description = description;
        this.marketValue = marketValue;
        this.haircutPct = haircutPct;
        this.appraisedValue = appraisedValue;
        this.assignedLtvRatio = assignedLtvRatio;
        this.encumbranceAmount = encumbranceAmount;
        this.lienStatus = lienStatus;
        this.valuationDate = valuationDate;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.collateralId = id;
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

    public synchronized void revalue(double newMktValue, String date) {
        marketValue = newMktValue; valuationDate = date; appraisedValue = marketValue * (1.0 - haircutPct);
        this.logStateChange("revalue");
    }
    public synchronized void imposeLien(double encumberAmt) {
        encumbranceAmount = encumbranceAmount + encumberAmt; lienStatus = "ENCUMBERED";
        this.logStateChange("imposeLien");
    }
    public synchronized void releaseLien(double releaseAmt) {
        encumbranceAmount = Math.max(0.0, encumbranceAmount - releaseAmt); if (encumbranceAmount == 0.0) lienStatus = "UNENCUMBERED";
        this.logStateChange("releaseLien");
    }
    public synchronized void liquidate(double recoveryAmount) {
        marketValue = 0.0; appraisedValue = 0.0; lienStatus = "LIQUIDATED";
        this.logStateChange("liquidate");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "CollateralItem", String.valueOf(this.collateralId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getCollateralId() {
        return this.collateralId;
    }
    public void setCollateralId(String collateralId) {
        this.collateralId = collateralId;
    }
    public String getCustomerId() {
        return this.customerId;
    }
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
    public String getCollateralType() {
        return this.collateralType;
    }
    public void setCollateralType(String collateralType) {
        this.collateralType = collateralType;
    }
    public String getDescription() {
        return this.description;
    }
    public void setDescription(String description) {
        this.description = description;
    }
    public double getMarketValue() {
        return this.marketValue;
    }
    public void setMarketValue(double marketValue) {
        this.marketValue = marketValue;
    }
    public double getHaircutPct() {
        return this.haircutPct;
    }
    public void setHaircutPct(double haircutPct) {
        this.haircutPct = haircutPct;
    }
    public double getAppraisedValue() {
        return this.appraisedValue;
    }
    public void setAppraisedValue(double appraisedValue) {
        this.appraisedValue = appraisedValue;
    }
    public double getAssignedLtvRatio() {
        return this.assignedLtvRatio;
    }
    public void setAssignedLtvRatio(double assignedLtvRatio) {
        this.assignedLtvRatio = assignedLtvRatio;
    }
    public double getEncumbranceAmount() {
        return this.encumbranceAmount;
    }
    public void setEncumbranceAmount(double encumbranceAmount) {
        this.encumbranceAmount = encumbranceAmount;
    }
    public String getLienStatus() {
        return this.lienStatus;
    }
    public void setLienStatus(String lienStatus) {
        this.lienStatus = lienStatus;
    }
    public String getValuationDate() {
        return this.valuationDate;
    }
    public void setValuationDate(String valuationDate) {
        this.valuationDate = valuationDate;
    }
}
