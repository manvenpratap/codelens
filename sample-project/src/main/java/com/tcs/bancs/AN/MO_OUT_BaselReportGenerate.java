package com.tcs.bancs.AN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_BaselReportGenerate
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_BaselReportGenerate implements Serializable {

    private static final long serialVersionUID = 1L;

    private String reportId;
    private double carRatio;
    private double rwa;
    private String status;
    private String messageCorrelationId;

    public MO_OUT_BaselReportGenerate() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_BaselReportGenerate(String reportId, double carRatio, double rwa, String status) {
        this();
        this.reportId = reportId;
        this.carRatio = carRatio;
        this.rwa = rwa;
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
    public double getCarRatio() {
        return this.carRatio;
    }
    public void setCarRatio(double carRatio) {
        this.carRatio = carRatio;
    }
    public double getRwa() {
        return this.rwa;
    }
    public void setRwa(double rwa) {
        this.rwa = rwa;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "MO_OUT_BaselReportGenerate{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
