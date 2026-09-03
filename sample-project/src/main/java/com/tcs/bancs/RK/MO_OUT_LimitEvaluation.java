package com.tcs.bancs.RK;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_LimitEvaluation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_LimitEvaluation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String partyId;
    private boolean approved;
    private double availableHeadroom;
    private String reasonCode;
    private String messageCorrelationId;

    public MO_OUT_LimitEvaluation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_LimitEvaluation(String partyId, boolean approved, double availableHeadroom, String reasonCode) {
        this();
        this.partyId = partyId;
        this.approved = approved;
        this.availableHeadroom = availableHeadroom;
        this.reasonCode = reasonCode;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getPartyId() {
        return this.partyId;
    }
    public void setPartyId(String partyId) {
        this.partyId = partyId;
    }
    public boolean getApproved() {
        return this.approved;
    }
    public void setApproved(boolean approved) {
        this.approved = approved;
    }
    public double getAvailableHeadroom() {
        return this.availableHeadroom;
    }
    public void setAvailableHeadroom(double availableHeadroom) {
        this.availableHeadroom = availableHeadroom;
    }
    public String getReasonCode() {
        return this.reasonCode;
    }
    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    @Override
    public String toString() {
        return "MO_OUT_LimitEvaluation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
