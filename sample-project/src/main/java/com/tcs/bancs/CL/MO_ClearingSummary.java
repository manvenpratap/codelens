package com.tcs.bancs.CL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_ClearingSummary
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_ClearingSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    private String batchId;
    private int totalTrades;
    private double totalGrossVolume;
    private double nettingEfficiency;
    private String messageCorrelationId;

    public MO_ClearingSummary() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_ClearingSummary(String batchId, int totalTrades, double totalGrossVolume, double nettingEfficiency) {
        this();
        this.batchId = batchId;
        this.totalTrades = totalTrades;
        this.totalGrossVolume = totalGrossVolume;
        this.nettingEfficiency = nettingEfficiency;
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
    public int getTotalTrades() {
        return this.totalTrades;
    }
    public void setTotalTrades(int totalTrades) {
        this.totalTrades = totalTrades;
    }
    public double getTotalGrossVolume() {
        return this.totalGrossVolume;
    }
    public void setTotalGrossVolume(double totalGrossVolume) {
        this.totalGrossVolume = totalGrossVolume;
    }
    public double getNettingEfficiency() {
        return this.nettingEfficiency;
    }
    public void setNettingEfficiency(double nettingEfficiency) {
        this.nettingEfficiency = nettingEfficiency;
    }

    @Override
    public String toString() {
        return "MO_ClearingSummary{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
