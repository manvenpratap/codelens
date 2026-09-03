package com.tcs.bancs.RK;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_CounterpartyRating
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_CounterpartyRating implements Serializable {

    private static final long serialVersionUID = 1L;

    private String partyId;
    private String creditRating;
    private double defaultProbability;
    private String messageCorrelationId;

    public MO_CounterpartyRating() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_CounterpartyRating(String partyId, String creditRating, double defaultProbability) {
        this();
        this.partyId = partyId;
        this.creditRating = creditRating;
        this.defaultProbability = defaultProbability;
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
    public String getCreditRating() {
        return this.creditRating;
    }
    public void setCreditRating(String creditRating) {
        this.creditRating = creditRating;
    }
    public double getDefaultProbability() {
        return this.defaultProbability;
    }
    public void setDefaultProbability(double defaultProbability) {
        this.defaultProbability = defaultProbability;
    }

    @Override
    public String toString() {
        return "MO_CounterpartyRating{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
