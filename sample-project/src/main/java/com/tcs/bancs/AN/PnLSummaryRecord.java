package com.tcs.bancs.AN;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: PnLSummaryRecord
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class PnLSummaryRecord {

    private String pnlId;
    private String businessDate;
    private String deskId;
    private String portfolioId;
    private double realizedPnL;
    private double unrealizedPnL;
    private double feeIncome;
    private double interestExpense;
    private double totalNetPnL;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public PnLSummaryRecord() {
    }

    public PnLSummaryRecord(String pnlId, String businessDate, String deskId, String portfolioId, double realizedPnL, double unrealizedPnL, double feeIncome, double interestExpense, double totalNetPnL) {
        this.pnlId = pnlId;
        this.businessDate = businessDate;
        this.deskId = deskId;
        this.portfolioId = portfolioId;
        this.realizedPnL = realizedPnL;
        this.unrealizedPnL = unrealizedPnL;
        this.feeIncome = feeIncome;
        this.interestExpense = interestExpense;
        this.totalNetPnL = totalNetPnL;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.pnlId = id;
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

    public synchronized void recalculateAttribution(double realized, double unrealized) {
        realizedPnL = realized; unrealizedPnL = unrealized; totalNetPnL = realizedPnL + unrealizedPnL + feeIncome - interestExpense;
        this.logStateChange("recalculateAttribution");
    }
    public synchronized void publishMetrics(String date) {
        businessDate = date;
        this.logStateChange("publishMetrics");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "PnLSummaryRecord", String.valueOf(this.pnlId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getPnlId() {
        return this.pnlId;
    }
    public void setPnlId(String pnlId) {
        this.pnlId = pnlId;
    }
    public String getBusinessDate() {
        return this.businessDate;
    }
    public void setBusinessDate(String businessDate) {
        this.businessDate = businessDate;
    }
    public String getDeskId() {
        return this.deskId;
    }
    public void setDeskId(String deskId) {
        this.deskId = deskId;
    }
    public String getPortfolioId() {
        return this.portfolioId;
    }
    public void setPortfolioId(String portfolioId) {
        this.portfolioId = portfolioId;
    }
    public double getRealizedPnL() {
        return this.realizedPnL;
    }
    public void setRealizedPnL(double realizedPnL) {
        this.realizedPnL = realizedPnL;
    }
    public double getUnrealizedPnL() {
        return this.unrealizedPnL;
    }
    public void setUnrealizedPnL(double unrealizedPnL) {
        this.unrealizedPnL = unrealizedPnL;
    }
    public double getFeeIncome() {
        return this.feeIncome;
    }
    public void setFeeIncome(double feeIncome) {
        this.feeIncome = feeIncome;
    }
    public double getInterestExpense() {
        return this.interestExpense;
    }
    public void setInterestExpense(double interestExpense) {
        this.interestExpense = interestExpense;
    }
    public double getTotalNetPnL() {
        return this.totalNetPnL;
    }
    public void setTotalNetPnL(double totalNetPnL) {
        this.totalNetPnL = totalNetPnL;
    }
}
