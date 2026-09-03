package com.tcs.bancs.GL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_IntercompanyClearingEntry
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_IntercompanyClearingEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private String fromEntity;
    private String toEntity;
    private double amount;
    private String messageCorrelationId;

    public MO_IntercompanyClearingEntry() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_IntercompanyClearingEntry(String fromEntity, String toEntity, double amount) {
        this();
        this.fromEntity = fromEntity;
        this.toEntity = toEntity;
        this.amount = amount;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getFromEntity() {
        return this.fromEntity;
    }
    public void setFromEntity(String fromEntity) {
        this.fromEntity = fromEntity;
    }
    public String getToEntity() {
        return this.toEntity;
    }
    public void setToEntity(String toEntity) {
        this.toEntity = toEntity;
    }
    public double getAmount() {
        return this.amount;
    }
    public void setAmount(double amount) {
        this.amount = amount;
    }

    @Override
    public String toString() {
        return "MO_IntercompanyClearingEntry{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
