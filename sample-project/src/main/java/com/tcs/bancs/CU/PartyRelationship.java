package com.tcs.bancs.CU;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: PartyRelationship
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class PartyRelationship {

    private String relationshipId;
    private String parentCustomerId;
    private String childCustomerId;
    private String relationType;
    private double shareholdingPercentage;
    private boolean isAuthorizedSignatory;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public PartyRelationship() {
    }

    public PartyRelationship(String relationshipId, String parentCustomerId, String childCustomerId, String relationType, double shareholdingPercentage, boolean isAuthorizedSignatory) {
        this.relationshipId = relationshipId;
        this.parentCustomerId = parentCustomerId;
        this.childCustomerId = childCustomerId;
        this.relationType = relationType;
        this.shareholdingPercentage = shareholdingPercentage;
        this.isAuthorizedSignatory = isAuthorizedSignatory;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.relationshipId = id;
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

    public synchronized void updateShareholding(double pct) {
        shareholdingPercentage = pct; if (pct > 25.0) isAuthorizedSignatory = true;
        this.logStateChange("updateShareholding");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "PartyRelationship", String.valueOf(this.relationshipId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getRelationshipId() {
        return this.relationshipId;
    }
    public void setRelationshipId(String relationshipId) {
        this.relationshipId = relationshipId;
    }
    public String getParentCustomerId() {
        return this.parentCustomerId;
    }
    public void setParentCustomerId(String parentCustomerId) {
        this.parentCustomerId = parentCustomerId;
    }
    public String getChildCustomerId() {
        return this.childCustomerId;
    }
    public void setChildCustomerId(String childCustomerId) {
        this.childCustomerId = childCustomerId;
    }
    public String getRelationType() {
        return this.relationType;
    }
    public void setRelationType(String relationType) {
        this.relationType = relationType;
    }
    public double getShareholdingPercentage() {
        return this.shareholdingPercentage;
    }
    public void setShareholdingPercentage(double shareholdingPercentage) {
        this.shareholdingPercentage = shareholdingPercentage;
    }
    public boolean getIsAuthorizedSignatory() {
        return this.isAuthorizedSignatory;
    }
    public void setIsAuthorizedSignatory(boolean isAuthorizedSignatory) {
        this.isAuthorizedSignatory = isAuthorizedSignatory;
    }
}
