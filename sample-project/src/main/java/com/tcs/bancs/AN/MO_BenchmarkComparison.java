package com.tcs.bancs.AN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_BenchmarkComparison
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_BenchmarkComparison implements Serializable {

    private static final long serialVersionUID = 1L;

    private String portfolioId;
    private String benchmarkId;
    private double alpha;
    private double beta;
    private String messageCorrelationId;

    public MO_BenchmarkComparison() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_BenchmarkComparison(String portfolioId, String benchmarkId, double alpha, double beta) {
        this();
        this.portfolioId = portfolioId;
        this.benchmarkId = benchmarkId;
        this.alpha = alpha;
        this.beta = beta;
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
    public String getBenchmarkId() {
        return this.benchmarkId;
    }
    public void setBenchmarkId(String benchmarkId) {
        this.benchmarkId = benchmarkId;
    }
    public double getAlpha() {
        return this.alpha;
    }
    public void setAlpha(double alpha) {
        this.alpha = alpha;
    }
    public double getBeta() {
        return this.beta;
    }
    public void setBeta(double beta) {
        this.beta = beta;
    }

    @Override
    public String toString() {
        return "MO_BenchmarkComparison{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
