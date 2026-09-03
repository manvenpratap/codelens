package com.tcs.bancs.GL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_ReconciliationException
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_ReconciliationException implements Serializable {

    private static final long serialVersionUID = 1L;

    private String glCode;
    private double variance;
    private String reason;
    private String messageCorrelationId;

    public MO_ReconciliationException() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_ReconciliationException(String glCode, double variance, String reason) {
        this();
        this.glCode = glCode;
        this.variance = variance;
        this.reason = reason;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getGlCode() {
        return this.glCode;
    }
    public void setGlCode(String glCode) {
        this.glCode = glCode;
    }
    public double getVariance() {
        return this.variance;
    }
    public void setVariance(double variance) {
        this.variance = variance;
    }
    public String getReason() {
        return this.reason;
    }
    public void setReason(String reason) {
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "MO_ReconciliationException{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
