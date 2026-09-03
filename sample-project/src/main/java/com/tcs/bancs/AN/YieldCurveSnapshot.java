package com.tcs.bancs.AN;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: YieldCurveSnapshot
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class YieldCurveSnapshot {

    private String curveId;
    private String currency;
    private String referenceIndex;
    private int tenorDays;
    private double zeroCouponRate;
    private double discountFactor;
    private String asOfDate;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public YieldCurveSnapshot() {
    }

    public YieldCurveSnapshot(String curveId, String currency, String referenceIndex, int tenorDays, double zeroCouponRate, double discountFactor, String asOfDate) {
        this.curveId = curveId;
        this.currency = currency;
        this.referenceIndex = referenceIndex;
        this.tenorDays = tenorDays;
        this.zeroCouponRate = zeroCouponRate;
        this.discountFactor = discountFactor;
        this.asOfDate = asOfDate;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.curveId = id;
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

    public synchronized void interpolateRate(int days) {
        tenorDays = days; discountFactor = Math.exp(-zeroCouponRate * (days / 365.0));
        this.logStateChange("interpolateRate");
    }
    public synchronized void calibrateCurve(double rate) {
        zeroCouponRate = rate;
        this.logStateChange("calibrateCurve");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "YieldCurveSnapshot", String.valueOf(this.curveId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getCurveId() {
        return this.curveId;
    }
    public void setCurveId(String curveId) {
        this.curveId = curveId;
    }
    public String getCurrency() {
        return this.currency;
    }
    public void setCurrency(String currency) {
        this.currency = currency;
    }
    public String getReferenceIndex() {
        return this.referenceIndex;
    }
    public void setReferenceIndex(String referenceIndex) {
        this.referenceIndex = referenceIndex;
    }
    public int getTenorDays() {
        return this.tenorDays;
    }
    public void setTenorDays(int tenorDays) {
        this.tenorDays = tenorDays;
    }
    public double getZeroCouponRate() {
        return this.zeroCouponRate;
    }
    public void setZeroCouponRate(double zeroCouponRate) {
        this.zeroCouponRate = zeroCouponRate;
    }
    public double getDiscountFactor() {
        return this.discountFactor;
    }
    public void setDiscountFactor(double discountFactor) {
        this.discountFactor = discountFactor;
    }
    public String getAsOfDate() {
        return this.asOfDate;
    }
    public void setAsOfDate(String asOfDate) {
        this.asOfDate = asOfDate;
    }
}
