package com.tcs.bancs.CU;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_CustomerOnboarding
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_CustomerOnboarding implements Serializable {

    private static final long serialVersionUID = 1L;

    private String customerId;
    private String status;
    private String assignedRiskRating;
    private long timestamp;
    private String messageCorrelationId;

    public MO_OUT_CustomerOnboarding() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_CustomerOnboarding(String customerId, String status, String assignedRiskRating, long timestamp) {
        this();
        this.customerId = customerId;
        this.status = status;
        this.assignedRiskRating = assignedRiskRating;
        this.timestamp = timestamp;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getCustomerId() {
        return this.customerId;
    }
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public String getAssignedRiskRating() {
        return this.assignedRiskRating;
    }
    public void setAssignedRiskRating(String assignedRiskRating) {
        this.assignedRiskRating = assignedRiskRating;
    }
    public long getTimestamp() {
        return this.timestamp;
    }
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "MO_OUT_CustomerOnboarding{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
