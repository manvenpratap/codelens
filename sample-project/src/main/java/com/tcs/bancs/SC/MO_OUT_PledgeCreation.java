package com.tcs.bancs.SC;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_PledgeCreation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_PledgeCreation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String pledgeId;
    private String status;
    private double remainingAvailableCollateral;
    private String messageCorrelationId;

    public MO_OUT_PledgeCreation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_PledgeCreation(String pledgeId, String status, double remainingAvailableCollateral) {
        this();
        this.pledgeId = pledgeId;
        this.status = status;
        this.remainingAvailableCollateral = remainingAvailableCollateral;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getPledgeId() {
        return this.pledgeId;
    }
    public void setPledgeId(String pledgeId) {
        this.pledgeId = pledgeId;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public double getRemainingAvailableCollateral() {
        return this.remainingAvailableCollateral;
    }
    public void setRemainingAvailableCollateral(double remainingAvailableCollateral) {
        this.remainingAvailableCollateral = remainingAvailableCollateral;
    }

    @Override
    public String toString() {
        return "MO_OUT_PledgeCreation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
