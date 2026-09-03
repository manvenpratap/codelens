package com.tcs.bancs.PM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_DirectDebitBatch
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_DirectDebitBatch implements Serializable {

    private static final long serialVersionUID = 1L;

    private String batchId;
    private int acceptedCount;
    private int rejectedCount;
    private String messageCorrelationId;

    public MO_OUT_DirectDebitBatch() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_DirectDebitBatch(String batchId, int acceptedCount, int rejectedCount) {
        this();
        this.batchId = batchId;
        this.acceptedCount = acceptedCount;
        this.rejectedCount = rejectedCount;
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
    public int getAcceptedCount() {
        return this.acceptedCount;
    }
    public void setAcceptedCount(int acceptedCount) {
        this.acceptedCount = acceptedCount;
    }
    public int getRejectedCount() {
        return this.rejectedCount;
    }
    public void setRejectedCount(int rejectedCount) {
        this.rejectedCount = rejectedCount;
    }

    @Override
    public String toString() {
        return "MO_OUT_DirectDebitBatch{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
