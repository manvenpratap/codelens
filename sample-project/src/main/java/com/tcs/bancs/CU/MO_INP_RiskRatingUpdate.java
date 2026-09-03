package com.tcs.bancs.CU;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_RiskRatingUpdate
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_RiskRatingUpdate implements Serializable {

    private static final long serialVersionUID = 1L;

    private String customerId;
    private String proposedRating;
    private String rationale;
    private String messageCorrelationId;

    public MO_INP_RiskRatingUpdate() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_RiskRatingUpdate(String customerId, String proposedRating, String rationale) {
        this();
        this.customerId = customerId;
        this.proposedRating = proposedRating;
        this.rationale = rationale;
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
    public String getProposedRating() {
        return this.proposedRating;
    }
    public void setProposedRating(String proposedRating) {
        this.proposedRating = proposedRating;
    }
    public String getRationale() {
        return this.rationale;
    }
    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    @Override
    public String toString() {
        return "MO_INP_RiskRatingUpdate{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
