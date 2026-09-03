package com.tcs.bancs.CU;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_KycSubmission
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_KycSubmission implements Serializable {

    private static final long serialVersionUID = 1L;

    private String customerId;
    private String documentType;
    private String documentNumber;
    private String expiry;
    private String messageCorrelationId;

    public MO_INP_KycSubmission() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_KycSubmission(String customerId, String documentType, String documentNumber, String expiry) {
        this();
        this.customerId = customerId;
        this.documentType = documentType;
        this.documentNumber = documentNumber;
        this.expiry = expiry;
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
    public String getDocumentType() {
        return this.documentType;
    }
    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }
    public String getDocumentNumber() {
        return this.documentNumber;
    }
    public void setDocumentNumber(String documentNumber) {
        this.documentNumber = documentNumber;
    }
    public String getExpiry() {
        return this.expiry;
    }
    public void setExpiry(String expiry) {
        this.expiry = expiry;
    }

    @Override
    public String toString() {
        return "MO_INP_KycSubmission{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
