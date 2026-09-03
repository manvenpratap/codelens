package com.tcs.bancs.TR;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_AlgorithmicSignal
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_AlgorithmicSignal implements Serializable {

    private static final long serialVersionUID = 1L;

    private String strategyId;
    private String symbol;
    private String action;
    private double confidence;
    private String messageCorrelationId;

    public MO_AlgorithmicSignal() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_AlgorithmicSignal(String strategyId, String symbol, String action, double confidence) {
        this();
        this.strategyId = strategyId;
        this.symbol = symbol;
        this.action = action;
        this.confidence = confidence;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getStrategyId() {
        return this.strategyId;
    }
    public void setStrategyId(String strategyId) {
        this.strategyId = strategyId;
    }
    public String getSymbol() {
        return this.symbol;
    }
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
    public String getAction() {
        return this.action;
    }
    public void setAction(String action) {
        this.action = action;
    }
    public double getConfidence() {
        return this.confidence;
    }
    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    @Override
    public String toString() {
        return "MO_AlgorithmicSignal{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
