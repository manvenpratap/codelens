package com.tcs.bancs.RK;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_RiskOverride
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_RiskOverride implements Serializable {

    private static final long serialVersionUID = 1L;

    private String limitId;
    private double overrideAmount;
    private String approvedBy;
    private String messageCorrelationId;

    public MO_INP_RiskOverride() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_RiskOverride(String limitId, double overrideAmount, String approvedBy) {
        this();
        this.limitId = limitId;
        this.overrideAmount = overrideAmount;
        this.approvedBy = approvedBy;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getLimitId() {
        return this.limitId;
    }
    public void setLimitId(String limitId) {
        this.limitId = limitId;
    }
    public double getOverrideAmount() {
        return this.overrideAmount;
    }
    public void setOverrideAmount(double overrideAmount) {
        this.overrideAmount = overrideAmount;
    }
    public String getApprovedBy() {
        return this.approvedBy;
    }
    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    @Override
    public String toString() {
        return "MO_INP_RiskOverride{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
