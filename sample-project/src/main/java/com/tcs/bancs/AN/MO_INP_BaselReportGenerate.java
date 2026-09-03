package com.tcs.bancs.AN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_BaselReportGenerate
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_BaselReportGenerate implements Serializable {

    private static final long serialVersionUID = 1L;

    private String reportType;
    private String period;
    private String messageCorrelationId;

    public MO_INP_BaselReportGenerate() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_BaselReportGenerate(String reportType, String period) {
        this();
        this.reportType = reportType;
        this.period = period;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getReportType() {
        return this.reportType;
    }
    public void setReportType(String reportType) {
        this.reportType = reportType;
    }
    public String getPeriod() {
        return this.period;
    }
    public void setPeriod(String period) {
        this.period = period;
    }

    @Override
    public String toString() {
        return "MO_INP_BaselReportGenerate{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
