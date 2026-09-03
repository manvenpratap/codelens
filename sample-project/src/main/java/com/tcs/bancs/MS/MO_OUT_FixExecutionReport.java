package com.tcs.bancs.MS;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_FixExecutionReport
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_FixExecutionReport implements Serializable {

    private static final long serialVersionUID = 1L;

    private String execId;
    private String clOrdId;
    private String ordStatus;
    private int cumQty;
    private double avgPx;
    private String messageCorrelationId;

    public MO_OUT_FixExecutionReport() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_FixExecutionReport(String execId, String clOrdId, String ordStatus, int cumQty, double avgPx) {
        this();
        this.execId = execId;
        this.clOrdId = clOrdId;
        this.ordStatus = ordStatus;
        this.cumQty = cumQty;
        this.avgPx = avgPx;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getExecId() {
        return this.execId;
    }
    public void setExecId(String execId) {
        this.execId = execId;
    }
    public String getClOrdId() {
        return this.clOrdId;
    }
    public void setClOrdId(String clOrdId) {
        this.clOrdId = clOrdId;
    }
    public String getOrdStatus() {
        return this.ordStatus;
    }
    public void setOrdStatus(String ordStatus) {
        this.ordStatus = ordStatus;
    }
    public int getCumQty() {
        return this.cumQty;
    }
    public void setCumQty(int cumQty) {
        this.cumQty = cumQty;
    }
    public double getAvgPx() {
        return this.avgPx;
    }
    public void setAvgPx(double avgPx) {
        this.avgPx = avgPx;
    }

    @Override
    public String toString() {
        return "MO_OUT_FixExecutionReport{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
