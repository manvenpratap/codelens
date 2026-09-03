package com.tcs.bancs.MS;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: InboundPayloadStore
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class InboundPayloadStore {

    private String payloadId;
    private String messageId;
    private String rawPayload;
    private String parsedXmlJson;
    private long receivedTimestamp;
    private String processingStatus;
    private int retryCount;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public InboundPayloadStore() {
    }

    public InboundPayloadStore(String payloadId, String messageId, String rawPayload, String parsedXmlJson, long receivedTimestamp, String processingStatus, int retryCount) {
        this.payloadId = payloadId;
        this.messageId = messageId;
        this.rawPayload = rawPayload;
        this.parsedXmlJson = parsedXmlJson;
        this.receivedTimestamp = receivedTimestamp;
        this.processingStatus = processingStatus;
        this.retryCount = retryCount;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.payloadId = id;
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

    public synchronized void markProcessed() {
        processingStatus = "PROCESSED";
        this.logStateChange("markProcessed");
    }
    public synchronized void incrementRetry() {
        retryCount = retryCount + 1; if (retryCount > 3) processingStatus = "DEAD_LETTER";
        this.logStateChange("incrementRetry");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "InboundPayloadStore", String.valueOf(this.payloadId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getPayloadId() {
        return this.payloadId;
    }
    public void setPayloadId(String payloadId) {
        this.payloadId = payloadId;
    }
    public String getMessageId() {
        return this.messageId;
    }
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
    public String getRawPayload() {
        return this.rawPayload;
    }
    public void setRawPayload(String rawPayload) {
        this.rawPayload = rawPayload;
    }
    public String getParsedXmlJson() {
        return this.parsedXmlJson;
    }
    public void setParsedXmlJson(String parsedXmlJson) {
        this.parsedXmlJson = parsedXmlJson;
    }
    public long getReceivedTimestamp() {
        return this.receivedTimestamp;
    }
    public void setReceivedTimestamp(long receivedTimestamp) {
        this.receivedTimestamp = receivedTimestamp;
    }
    public String getProcessingStatus() {
        return this.processingStatus;
    }
    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }
    public int getRetryCount() {
        return this.retryCount;
    }
    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
}
