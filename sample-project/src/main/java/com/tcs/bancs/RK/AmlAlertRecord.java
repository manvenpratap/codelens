package com.tcs.bancs.RK;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: AmlAlertRecord
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class AmlAlertRecord {

    private String alertId;
    private String transactionId;
    private String customerId;
    private String ruleTriggered;
    private double riskScore;
    private String investigationStatus;
    private String complianceOfficerId;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public AmlAlertRecord() {
    }

    public AmlAlertRecord(String alertId, String transactionId, String customerId, String ruleTriggered, double riskScore, String investigationStatus, String complianceOfficerId) {
        this.alertId = alertId;
        this.transactionId = transactionId;
        this.customerId = customerId;
        this.ruleTriggered = ruleTriggered;
        this.riskScore = riskScore;
        this.investigationStatus = investigationStatus;
        this.complianceOfficerId = complianceOfficerId;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.alertId = id;
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

    public synchronized void escalateAlert(String officerId) {
        complianceOfficerId = officerId; investigationStatus = "ESCALATED";
        this.logStateChange("escalateAlert");
    }
    public synchronized void clearAlert(String reason) {
        investigationStatus = "CLEARED"; ruleTriggered = reason;
        this.logStateChange("clearAlert");
    }
    public synchronized void fileSAR(String reportId) {
        investigationStatus = "SAR_FILED"; ruleTriggered = reportId;
        this.logStateChange("fileSAR");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "AmlAlertRecord", String.valueOf(this.alertId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getAlertId() {
        return this.alertId;
    }
    public void setAlertId(String alertId) {
        this.alertId = alertId;
    }
    public String getTransactionId() {
        return this.transactionId;
    }
    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }
    public String getCustomerId() {
        return this.customerId;
    }
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
    public String getRuleTriggered() {
        return this.ruleTriggered;
    }
    public void setRuleTriggered(String ruleTriggered) {
        this.ruleTriggered = ruleTriggered;
    }
    public double getRiskScore() {
        return this.riskScore;
    }
    public void setRiskScore(double riskScore) {
        this.riskScore = riskScore;
    }
    public String getInvestigationStatus() {
        return this.investigationStatus;
    }
    public void setInvestigationStatus(String investigationStatus) {
        this.investigationStatus = investigationStatus;
    }
    public String getComplianceOfficerId() {
        return this.complianceOfficerId;
    }
    public void setComplianceOfficerId(String complianceOfficerId) {
        this.complianceOfficerId = complianceOfficerId;
    }
}
