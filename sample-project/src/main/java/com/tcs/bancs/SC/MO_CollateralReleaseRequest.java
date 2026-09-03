package com.tcs.bancs.SC;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_CollateralReleaseRequest
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_CollateralReleaseRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String pledgeId;
    private String reason;
    private String messageCorrelationId;

    public MO_CollateralReleaseRequest() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_CollateralReleaseRequest(String pledgeId, String reason) {
        this();
        this.pledgeId = pledgeId;
        this.reason = reason;
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
    public String getReason() {
        return this.reason;
    }
    public void setReason(String reason) {
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "MO_CollateralReleaseRequest{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
