package com.tcs.bancs.TR;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_TradeAllocation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_TradeAllocation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String allocationId;
    private String tradeId;
    private String status;
    private String messageCorrelationId;

    public MO_OUT_TradeAllocation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_TradeAllocation(String allocationId, String tradeId, String status) {
        this();
        this.allocationId = allocationId;
        this.tradeId = tradeId;
        this.status = status;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getAllocationId() {
        return this.allocationId;
    }
    public void setAllocationId(String allocationId) {
        this.allocationId = allocationId;
    }
    public String getTradeId() {
        return this.tradeId;
    }
    public void setTradeId(String tradeId) {
        this.tradeId = tradeId;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "MO_OUT_TradeAllocation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
