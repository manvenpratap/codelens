package com.tcs.bancs.AN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_LiquidityStressCheck
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_LiquidityStressCheck implements Serializable {

    private static final long serialVersionUID = 1L;

    private double projectedLcr;
    private boolean isCompliant;
    private String messageCorrelationId;

    public MO_OUT_LiquidityStressCheck() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_LiquidityStressCheck(double projectedLcr, boolean isCompliant) {
        this();
        this.projectedLcr = projectedLcr;
        this.isCompliant = isCompliant;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public double getProjectedLcr() {
        return this.projectedLcr;
    }
    public void setProjectedLcr(double projectedLcr) {
        this.projectedLcr = projectedLcr;
    }
    public boolean getIsCompliant() {
        return this.isCompliant;
    }
    public void setIsCompliant(boolean isCompliant) {
        this.isCompliant = isCompliant;
    }

    @Override
    public String toString() {
        return "MO_OUT_LiquidityStressCheck{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
