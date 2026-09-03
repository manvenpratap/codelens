package com.tcs.bancs.SC;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_MarginCallIssue
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_MarginCallIssue implements Serializable {

    private static final long serialVersionUID = 1L;

    private String facilityId;
    private double requiredMargin;
    private double deficit;
    private String messageCorrelationId;

    public MO_INP_MarginCallIssue() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_MarginCallIssue(String facilityId, double requiredMargin, double deficit) {
        this();
        this.facilityId = facilityId;
        this.requiredMargin = requiredMargin;
        this.deficit = deficit;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getFacilityId() {
        return this.facilityId;
    }
    public void setFacilityId(String facilityId) {
        this.facilityId = facilityId;
    }
    public double getRequiredMargin() {
        return this.requiredMargin;
    }
    public void setRequiredMargin(double requiredMargin) {
        this.requiredMargin = requiredMargin;
    }
    public double getDeficit() {
        return this.deficit;
    }
    public void setDeficit(double deficit) {
        this.deficit = deficit;
    }

    @Override
    public String toString() {
        return "MO_INP_MarginCallIssue{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
