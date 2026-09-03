package com.tcs.bancs.RK;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_RiskMetricSummary
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_RiskMetricSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    private String portfolioId;
    private double totalVaR;
    private double expectedShortfall;
    private double stressLoss;
    private String messageCorrelationId;

    public MO_RiskMetricSummary() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_RiskMetricSummary(String portfolioId, double totalVaR, double expectedShortfall, double stressLoss) {
        this();
        this.portfolioId = portfolioId;
        this.totalVaR = totalVaR;
        this.expectedShortfall = expectedShortfall;
        this.stressLoss = stressLoss;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getPortfolioId() {
        return this.portfolioId;
    }
    public void setPortfolioId(String portfolioId) {
        this.portfolioId = portfolioId;
    }
    public double getTotalVaR() {
        return this.totalVaR;
    }
    public void setTotalVaR(double totalVaR) {
        this.totalVaR = totalVaR;
    }
    public double getExpectedShortfall() {
        return this.expectedShortfall;
    }
    public void setExpectedShortfall(double expectedShortfall) {
        this.expectedShortfall = expectedShortfall;
    }
    public double getStressLoss() {
        return this.stressLoss;
    }
    public void setStressLoss(double stressLoss) {
        this.stressLoss = stressLoss;
    }

    @Override
    public String toString() {
        return "MO_RiskMetricSummary{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
