package com.tcs.bancs.CL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_NettingRequest
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_NettingRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String clearingMemberId;
    private String batchDate;
    private String cycle;
    private String messageCorrelationId;

    public MO_INP_NettingRequest() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_NettingRequest(String clearingMemberId, String batchDate, String cycle) {
        this();
        this.clearingMemberId = clearingMemberId;
        this.batchDate = batchDate;
        this.cycle = cycle;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getClearingMemberId() {
        return this.clearingMemberId;
    }
    public void setClearingMemberId(String clearingMemberId) {
        this.clearingMemberId = clearingMemberId;
    }
    public String getBatchDate() {
        return this.batchDate;
    }
    public void setBatchDate(String batchDate) {
        this.batchDate = batchDate;
    }
    public String getCycle() {
        return this.cycle;
    }
    public void setCycle(String cycle) {
        this.cycle = cycle;
    }

    @Override
    public String toString() {
        return "MO_INP_NettingRequest{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
