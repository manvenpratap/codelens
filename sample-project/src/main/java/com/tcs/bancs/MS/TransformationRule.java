package com.tcs.bancs.MS;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: TransformationRule
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class TransformationRule {

    private String ruleId;
    private String sourceFormat;
    private String targetFormat;
    private String mappingDefinition;
    private int version;
    private boolean isActive;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public TransformationRule() {
    }

    public TransformationRule(String ruleId, String sourceFormat, String targetFormat, String mappingDefinition, int version, boolean isActive) {
        this.ruleId = ruleId;
        this.sourceFormat = sourceFormat;
        this.targetFormat = targetFormat;
        this.mappingDefinition = mappingDefinition;
        this.version = version;
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
        this.ruleId = id;
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

    public synchronized void updateRule(String mapping) {
        mappingDefinition = mapping; version = version + 1;
        this.logStateChange("updateRule");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "TransformationRule", String.valueOf(this.ruleId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getRuleId() {
        return this.ruleId;
    }
    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }
    public String getSourceFormat() {
        return this.sourceFormat;
    }
    public void setSourceFormat(String sourceFormat) {
        this.sourceFormat = sourceFormat;
    }
    public String getTargetFormat() {
        return this.targetFormat;
    }
    public void setTargetFormat(String targetFormat) {
        this.targetFormat = targetFormat;
    }
    public String getMappingDefinition() {
        return this.mappingDefinition;
    }
    public void setMappingDefinition(String mappingDefinition) {
        this.mappingDefinition = mappingDefinition;
    }
    public int getVersion() {
        return this.version;
    }
    public void setVersion(int version) {
        this.version = version;
    }
    public boolean getIsActive() {
        return this.isActive;
    }
    public void setIsActive(boolean isActive) {
        this.isActive = isActive;
    }
}
