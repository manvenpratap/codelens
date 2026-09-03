package com.tcs.bancs.GL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_JournalEntry
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_JournalEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private String voucherId;
    private String status;
    private double balancedAmount;
    private long timestamp;
    private String messageCorrelationId;

    public MO_OUT_JournalEntry() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_JournalEntry(String voucherId, String status, double balancedAmount, long timestamp) {
        this();
        this.voucherId = voucherId;
        this.status = status;
        this.balancedAmount = balancedAmount;
        this.timestamp = timestamp;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getVoucherId() {
        return this.voucherId;
    }
    public void setVoucherId(String voucherId) {
        this.voucherId = voucherId;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public double getBalancedAmount() {
        return this.balancedAmount;
    }
    public void setBalancedAmount(double balancedAmount) {
        this.balancedAmount = balancedAmount;
    }
    public long getTimestamp() {
        return this.timestamp;
    }
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "MO_OUT_JournalEntry{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
