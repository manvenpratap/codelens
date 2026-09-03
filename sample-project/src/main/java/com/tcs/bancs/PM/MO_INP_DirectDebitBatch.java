package com.tcs.bancs.PM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_DirectDebitBatch
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_DirectDebitBatch implements Serializable {

    private static final long serialVersionUID = 1L;

    private String creditorId;
    private int mandateCount;
    private double totalDebitSum;
    private String messageCorrelationId;

    public MO_INP_DirectDebitBatch() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_DirectDebitBatch(String creditorId, int mandateCount, double totalDebitSum) {
        this();
        this.creditorId = creditorId;
        this.mandateCount = mandateCount;
        this.totalDebitSum = totalDebitSum;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getCreditorId() {
        return this.creditorId;
    }
    public void setCreditorId(String creditorId) {
        this.creditorId = creditorId;
    }
    public int getMandateCount() {
        return this.mandateCount;
    }
    public void setMandateCount(int mandateCount) {
        this.mandateCount = mandateCount;
    }
    public double getTotalDebitSum() {
        return this.totalDebitSum;
    }
    public void setTotalDebitSum(double totalDebitSum) {
        this.totalDebitSum = totalDebitSum;
    }

    @Override
    public String toString() {
        return "MO_INP_DirectDebitBatch{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
