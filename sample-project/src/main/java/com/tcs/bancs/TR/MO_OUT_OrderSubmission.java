package com.tcs.bancs.TR;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_OrderSubmission
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_OrderSubmission implements Serializable {

    private static final long serialVersionUID = 1L;

    private String orderId;
    private String status;
    private String symbol;
    private int acceptedQty;
    private long ackTimestamp;
    private String messageCorrelationId;

    public MO_OUT_OrderSubmission() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_OrderSubmission(String orderId, String status, String symbol, int acceptedQty, long ackTimestamp) {
        this();
        this.orderId = orderId;
        this.status = status;
        this.symbol = symbol;
        this.acceptedQty = acceptedQty;
        this.ackTimestamp = ackTimestamp;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getOrderId() {
        return this.orderId;
    }
    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public String getSymbol() {
        return this.symbol;
    }
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
    public int getAcceptedQty() {
        return this.acceptedQty;
    }
    public void setAcceptedQty(int acceptedQty) {
        this.acceptedQty = acceptedQty;
    }
    public long getAckTimestamp() {
        return this.ackTimestamp;
    }
    public void setAckTimestamp(long ackTimestamp) {
        this.ackTimestamp = ackTimestamp;
    }

    @Override
    public String toString() {
        return "MO_OUT_OrderSubmission{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
