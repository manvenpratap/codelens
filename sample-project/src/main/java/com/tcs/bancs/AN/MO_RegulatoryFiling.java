package com.tcs.bancs.AN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_RegulatoryFiling
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_RegulatoryFiling implements Serializable {

    private static final long serialVersionUID = 1L;

    private String reportId;
    private String regulatorName;
    private String filingRef;
    private String status;
    private String messageCorrelationId;

    public MO_RegulatoryFiling() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_RegulatoryFiling(String reportId, String regulatorName, String filingRef, String status) {
        this();
        this.reportId = reportId;
        this.regulatorName = regulatorName;
        this.filingRef = filingRef;
        this.status = status;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getReportId() {
        return this.reportId;
    }
    public void setReportId(String reportId) {
        this.reportId = reportId;
    }
    public String getRegulatorName() {
        return this.regulatorName;
    }
    public void setRegulatorName(String regulatorName) {
        this.regulatorName = regulatorName;
    }
    public String getFilingRef() {
        return this.filingRef;
    }
    public void setFilingRef(String filingRef) {
        this.filingRef = filingRef;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "MO_RegulatoryFiling{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
