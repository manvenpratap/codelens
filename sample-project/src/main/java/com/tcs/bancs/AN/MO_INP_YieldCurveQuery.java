package com.tcs.bancs.AN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_YieldCurveQuery
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_YieldCurveQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private String currency;
    private String index;
    private int tenorDays;
    private String messageCorrelationId;

    public MO_INP_YieldCurveQuery() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_YieldCurveQuery(String currency, String index, int tenorDays) {
        this();
        this.currency = currency;
        this.index = index;
        this.tenorDays = tenorDays;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getCurrency() {
        return this.currency;
    }
    public void setCurrency(String currency) {
        this.currency = currency;
    }
    public String getIndex() {
        return this.index;
    }
    public void setIndex(String index) {
        this.index = index;
    }
    public int getTenorDays() {
        return this.tenorDays;
    }
    public void setTenorDays(int tenorDays) {
        this.tenorDays = tenorDays;
    }

    @Override
    public String toString() {
        return "MO_INP_YieldCurveQuery{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
