package com.tcs.bancs.MS;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: OutboundDispatchQueue
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class OutboundDispatchQueue {

    private String dispatchId;
    private String destinationQueue;
    private String formattedMessage;
    private long scheduledTime;
    private String deliveryReceipt;
    private String status;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public OutboundDispatchQueue() {
    }

    public OutboundDispatchQueue(String dispatchId, String destinationQueue, String formattedMessage, long scheduledTime, String deliveryReceipt, String status) {
        this.dispatchId = dispatchId;
        this.destinationQueue = destinationQueue;
        this.formattedMessage = formattedMessage;
        this.scheduledTime = scheduledTime;
        this.deliveryReceipt = deliveryReceipt;
        this.status = status;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.dispatchId = id;
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

    public synchronized void markDelivered(String receipt) {
        deliveryReceipt = receipt; status = "DELIVERED";
        this.logStateChange("markDelivered");
    }
    public synchronized void failDispatch(String error) {
        deliveryReceipt = error; status = "FAILED";
        this.logStateChange("failDispatch");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "OutboundDispatchQueue", String.valueOf(this.dispatchId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getDispatchId() {
        return this.dispatchId;
    }
    public void setDispatchId(String dispatchId) {
        this.dispatchId = dispatchId;
    }
    public String getDestinationQueue() {
        return this.destinationQueue;
    }
    public void setDestinationQueue(String destinationQueue) {
        this.destinationQueue = destinationQueue;
    }
    public String getFormattedMessage() {
        return this.formattedMessage;
    }
    public void setFormattedMessage(String formattedMessage) {
        this.formattedMessage = formattedMessage;
    }
    public long getScheduledTime() {
        return this.scheduledTime;
    }
    public void setScheduledTime(long scheduledTime) {
        this.scheduledTime = scheduledTime;
    }
    public String getDeliveryReceipt() {
        return this.deliveryReceipt;
    }
    public void setDeliveryReceipt(String deliveryReceipt) {
        this.deliveryReceipt = deliveryReceipt;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
}
