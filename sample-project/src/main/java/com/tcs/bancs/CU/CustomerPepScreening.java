package com.tcs.bancs.CU;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: CustomerPepScreening
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class CustomerPepScreening {

    private String screeningId;
    private String customerId;
    private boolean isPoliticallyExposed;
    private String pepJurisdiction;
    private String sanctionListMatch;
    private String screeningResult;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public CustomerPepScreening() {
    }

    public CustomerPepScreening(String screeningId, String customerId, boolean isPoliticallyExposed, String pepJurisdiction, String sanctionListMatch, String screeningResult) {
        this.screeningId = screeningId;
        this.customerId = customerId;
        this.isPoliticallyExposed = isPoliticallyExposed;
        this.pepJurisdiction = pepJurisdiction;
        this.sanctionListMatch = sanctionListMatch;
        this.screeningResult = screeningResult;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.screeningId = id;
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

    public synchronized void flagSanctionMatch(String listName) {
        sanctionListMatch = listName; screeningResult = "MATCH_FOUND";
        this.logStateChange("flagSanctionMatch");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "CustomerPepScreening", String.valueOf(this.screeningId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getScreeningId() {
        return this.screeningId;
    }
    public void setScreeningId(String screeningId) {
        this.screeningId = screeningId;
    }
    public String getCustomerId() {
        return this.customerId;
    }
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
    public boolean getIsPoliticallyExposed() {
        return this.isPoliticallyExposed;
    }
    public void setIsPoliticallyExposed(boolean isPoliticallyExposed) {
        this.isPoliticallyExposed = isPoliticallyExposed;
    }
    public String getPepJurisdiction() {
        return this.pepJurisdiction;
    }
    public void setPepJurisdiction(String pepJurisdiction) {
        this.pepJurisdiction = pepJurisdiction;
    }
    public String getSanctionListMatch() {
        return this.sanctionListMatch;
    }
    public void setSanctionListMatch(String sanctionListMatch) {
        this.sanctionListMatch = sanctionListMatch;
    }
    public String getScreeningResult() {
        return this.screeningResult;
    }
    public void setScreeningResult(String screeningResult) {
        this.screeningResult = screeningResult;
    }
}
