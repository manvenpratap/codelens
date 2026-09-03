package com.tcs.bancs.CU;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_KycDocumentSummary
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_KycDocumentSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    private String docId;
    private String type;
    private String status;
    private String expiry;
    private String messageCorrelationId;

    public MO_KycDocumentSummary() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_KycDocumentSummary(String docId, String type, String status, String expiry) {
        this();
        this.docId = docId;
        this.type = type;
        this.status = status;
        this.expiry = expiry;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getDocId() {
        return this.docId;
    }
    public void setDocId(String docId) {
        this.docId = docId;
    }
    public String getType() {
        return this.type;
    }
    public void setType(String type) {
        this.type = type;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public String getExpiry() {
        return this.expiry;
    }
    public void setExpiry(String expiry) {
        this.expiry = expiry;
    }

    @Override
    public String toString() {
        return "MO_KycDocumentSummary{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
