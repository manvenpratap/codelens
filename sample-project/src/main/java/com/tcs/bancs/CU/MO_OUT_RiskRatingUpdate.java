package com.tcs.bancs.CU;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_RiskRatingUpdate
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_RiskRatingUpdate implements Serializable {

    private static final long serialVersionUID = 1L;

    private String customerId;
    private String newRating;
    private boolean overrideApproved;
    private String messageCorrelationId;

    public MO_OUT_RiskRatingUpdate() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_RiskRatingUpdate(String customerId, String newRating, boolean overrideApproved) {
        this();
        this.customerId = customerId;
        this.newRating = newRating;
        this.overrideApproved = overrideApproved;
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
    public String getNewRating() {
        return this.newRating;
    }
    public void setNewRating(String newRating) {
        this.newRating = newRating;
    }
    public boolean getOverrideApproved() {
        return this.overrideApproved;
    }
    public void setOverrideApproved(boolean overrideApproved) {
        this.overrideApproved = overrideApproved;
    }

    @Override
    public String toString() {
        return "MO_OUT_RiskRatingUpdate{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
