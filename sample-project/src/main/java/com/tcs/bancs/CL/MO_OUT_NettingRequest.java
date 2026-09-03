package com.tcs.bancs.CL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_NettingRequest
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_NettingRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String batchId;
    private double netCashAmount;
    private int netUnits;
    private String status;
    private String messageCorrelationId;

    public MO_OUT_NettingRequest() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_NettingRequest(String batchId, double netCashAmount, int netUnits, String status) {
        this();
        this.batchId = batchId;
        this.netCashAmount = netCashAmount;
        this.netUnits = netUnits;
        this.status = status;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getBatchId() {
        return this.batchId;
    }
    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }
    public double getNetCashAmount() {
        return this.netCashAmount;
    }
    public void setNetCashAmount(double netCashAmount) {
        this.netCashAmount = netCashAmount;
    }
    public int getNetUnits() {
        return this.netUnits;
    }
    public void setNetUnits(int netUnits) {
        this.netUnits = netUnits;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "MO_OUT_NettingRequest{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
