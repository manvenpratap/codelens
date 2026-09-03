package com.tcs.bancs.RK;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_ExposureRecalculate
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_ExposureRecalculate implements Serializable {

    private static final long serialVersionUID = 1L;

    private String counterpartyId;
    private double incrementalTradeAmount;
    private String messageCorrelationId;

    public MO_INP_ExposureRecalculate() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_ExposureRecalculate(String counterpartyId, double incrementalTradeAmount) {
        this();
        this.counterpartyId = counterpartyId;
        this.incrementalTradeAmount = incrementalTradeAmount;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getCounterpartyId() {
        return this.counterpartyId;
    }
    public void setCounterpartyId(String counterpartyId) {
        this.counterpartyId = counterpartyId;
    }
    public double getIncrementalTradeAmount() {
        return this.incrementalTradeAmount;
    }
    public void setIncrementalTradeAmount(double incrementalTradeAmount) {
        this.incrementalTradeAmount = incrementalTradeAmount;
    }

    @Override
    public String toString() {
        return "MO_INP_ExposureRecalculate{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
