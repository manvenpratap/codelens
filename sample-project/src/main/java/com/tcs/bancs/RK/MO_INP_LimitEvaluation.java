package com.tcs.bancs.RK;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_LimitEvaluation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_LimitEvaluation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String partyId;
    private String category;
    private double requestedAmount;
    private String messageCorrelationId;

    public MO_INP_LimitEvaluation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_LimitEvaluation(String partyId, String category, double requestedAmount) {
        this();
        this.partyId = partyId;
        this.category = category;
        this.requestedAmount = requestedAmount;
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
    public String getCategory() {
        return this.category;
    }
    public void setCategory(String category) {
        this.category = category;
    }
    public double getRequestedAmount() {
        return this.requestedAmount;
    }
    public void setRequestedAmount(double requestedAmount) {
        this.requestedAmount = requestedAmount;
    }

    @Override
    public String toString() {
        return "MO_INP_LimitEvaluation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
