package com.tcs.bancs.AN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_YieldCurveQuery
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_YieldCurveQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private String curveId;
    private double rate;
    private double discountFactor;
    private String messageCorrelationId;

    public MO_OUT_YieldCurveQuery() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_YieldCurveQuery(String curveId, double rate, double discountFactor) {
        this();
        this.curveId = curveId;
        this.rate = rate;
        this.discountFactor = discountFactor;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getCurveId() {
        return this.curveId;
    }
    public void setCurveId(String curveId) {
        this.curveId = curveId;
    }
    public double getRate() {
        return this.rate;
    }
    public void setRate(double rate) {
        this.rate = rate;
    }
    public double getDiscountFactor() {
        return this.discountFactor;
    }
    public void setDiscountFactor(double discountFactor) {
        this.discountFactor = discountFactor;
    }

    @Override
    public String toString() {
        return "MO_OUT_YieldCurveQuery{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
