package com.tcs.bancs.CU;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_KycSubmission
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_KycSubmission implements Serializable {

    private static final long serialVersionUID = 1L;

    private String documentId;
    private String status;
    private boolean passedOCR;
    private String messageCorrelationId;

    public MO_OUT_KycSubmission() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_KycSubmission(String documentId, String status, boolean passedOCR) {
        this();
        this.documentId = documentId;
        this.status = status;
        this.passedOCR = passedOCR;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getDocumentId() {
        return this.documentId;
    }
    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public boolean getPassedOCR() {
        return this.passedOCR;
    }
    public void setPassedOCR(boolean passedOCR) {
        this.passedOCR = passedOCR;
    }

    @Override
    public String toString() {
        return "MO_OUT_KycSubmission{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
