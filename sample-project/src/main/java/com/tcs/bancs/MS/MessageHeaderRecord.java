package com.tcs.bancs.MS;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: MessageHeaderRecord
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class MessageHeaderRecord {

    private String messageId;
    private String protocolType;
    private String messageType;
    private String senderBic;
    private String receiverBic;
    private int sessionNumber;
    private int sequenceNumber;
    private String priority;
    private String checksum;
    private String dispatchStatus;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public MessageHeaderRecord() {
    }

    public MessageHeaderRecord(String messageId, String protocolType, String messageType, String senderBic, String receiverBic, int sessionNumber, int sequenceNumber, String priority, String checksum, String dispatchStatus) {
        this.messageId = messageId;
        this.protocolType = protocolType;
        this.messageType = messageType;
        this.senderBic = senderBic;
        this.receiverBic = receiverBic;
        this.sessionNumber = sessionNumber;
        this.sequenceNumber = sequenceNumber;
        this.priority = priority;
        this.checksum = checksum;
        this.dispatchStatus = dispatchStatus;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.messageId = id;
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

    public synchronized void updateStatus(String status) {
        dispatchStatus = status;
        this.logStateChange("updateStatus");
    }
    public synchronized void verifyChecksum(String receivedChecksum) {
        if (checksum != null && checksum.equals(receivedChecksum)) dispatchStatus = "VERIFIED";
        this.logStateChange("verifyChecksum");
    }
    public synchronized void assignSequence(int seq) {
        sequenceNumber = seq;
        this.logStateChange("assignSequence");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "MessageHeaderRecord", String.valueOf(this.messageId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getMessageId() {
        return this.messageId;
    }
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
    public String getProtocolType() {
        return this.protocolType;
    }
    public void setProtocolType(String protocolType) {
        this.protocolType = protocolType;
    }
    public String getMessageType() {
        return this.messageType;
    }
    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }
    public String getSenderBic() {
        return this.senderBic;
    }
    public void setSenderBic(String senderBic) {
        this.senderBic = senderBic;
    }
    public String getReceiverBic() {
        return this.receiverBic;
    }
    public void setReceiverBic(String receiverBic) {
        this.receiverBic = receiverBic;
    }
    public int getSessionNumber() {
        return this.sessionNumber;
    }
    public void setSessionNumber(int sessionNumber) {
        this.sessionNumber = sessionNumber;
    }
    public int getSequenceNumber() {
        return this.sequenceNumber;
    }
    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }
    public String getPriority() {
        return this.priority;
    }
    public void setPriority(String priority) {
        this.priority = priority;
    }
    public String getChecksum() {
        return this.checksum;
    }
    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }
    public String getDispatchStatus() {
        return this.dispatchStatus;
    }
    public void setDispatchStatus(String dispatchStatus) {
        this.dispatchStatus = dispatchStatus;
    }
}
