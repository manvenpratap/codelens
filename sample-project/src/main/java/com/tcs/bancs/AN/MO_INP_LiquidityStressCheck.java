package com.tcs.bancs.AN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_LiquidityStressCheck
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_LiquidityStressCheck implements Serializable {

    private static final long serialVersionUID = 1L;

    private double outflowShockPct;
    private double inflowHaircutPct;
    private String messageCorrelationId;

    public MO_INP_LiquidityStressCheck() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_LiquidityStressCheck(double outflowShockPct, double inflowHaircutPct) {
        this();
        this.outflowShockPct = outflowShockPct;
        this.inflowHaircutPct = inflowHaircutPct;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public double getOutflowShockPct() {
        return this.outflowShockPct;
    }
    public void setOutflowShockPct(double outflowShockPct) {
        this.outflowShockPct = outflowShockPct;
    }
    public double getInflowHaircutPct() {
        return this.inflowHaircutPct;
    }
    public void setInflowHaircutPct(double inflowHaircutPct) {
        this.inflowHaircutPct = inflowHaircutPct;
    }

    @Override
    public String toString() {
        return "MO_INP_LiquidityStressCheck{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
