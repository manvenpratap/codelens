package com.tcs.bancs.TR;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_TradeAllocation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_TradeAllocation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tradeId;
    private String subAccountId;
    private int allocatedQuantity;
    private double allocatedPrice;
    private String messageCorrelationId;

    public MO_INP_TradeAllocation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_TradeAllocation(String tradeId, String subAccountId, int allocatedQuantity, double allocatedPrice) {
        this();
        this.tradeId = tradeId;
        this.subAccountId = subAccountId;
        this.allocatedQuantity = allocatedQuantity;
        this.allocatedPrice = allocatedPrice;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getTradeId() {
        return this.tradeId;
    }
    public void setTradeId(String tradeId) {
        this.tradeId = tradeId;
    }
    public String getSubAccountId() {
        return this.subAccountId;
    }
    public void setSubAccountId(String subAccountId) {
        this.subAccountId = subAccountId;
    }
    public int getAllocatedQuantity() {
        return this.allocatedQuantity;
    }
    public void setAllocatedQuantity(int allocatedQuantity) {
        this.allocatedQuantity = allocatedQuantity;
    }
    public double getAllocatedPrice() {
        return this.allocatedPrice;
    }
    public void setAllocatedPrice(double allocatedPrice) {
        this.allocatedPrice = allocatedPrice;
    }

    @Override
    public String toString() {
        return "MO_INP_TradeAllocation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
