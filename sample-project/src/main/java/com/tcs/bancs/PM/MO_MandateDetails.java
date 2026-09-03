package com.tcs.bancs.PM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_MandateDetails
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_MandateDetails implements Serializable {

    private static final long serialVersionUID = 1L;

    private String mandateId;
    private String debtor;
    private String creditor;
    private double limit;
    private String status;
    private String messageCorrelationId;

    public MO_MandateDetails() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_MandateDetails(String mandateId, String debtor, String creditor, double limit, String status) {
        this();
        this.mandateId = mandateId;
        this.debtor = debtor;
        this.creditor = creditor;
        this.limit = limit;
        this.status = status;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getMandateId() {
        return this.mandateId;
    }
    public void setMandateId(String mandateId) {
        this.mandateId = mandateId;
    }
    public String getDebtor() {
        return this.debtor;
    }
    public void setDebtor(String debtor) {
        this.debtor = debtor;
    }
    public String getCreditor() {
        return this.creditor;
    }
    public void setCreditor(String creditor) {
        this.creditor = creditor;
    }
    public double getLimit() {
        return this.limit;
    }
    public void setLimit(double limit) {
        this.limit = limit;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "MO_MandateDetails{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
