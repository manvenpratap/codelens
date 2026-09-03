package com.tcs.bancs.TR;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_OrderCancel
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_OrderCancel implements Serializable {

    private static final long serialVersionUID = 1L;

    private String orderId;
    private String cancelStatus;
    private int remainingCancelledQty;
    private String messageCorrelationId;

    public MO_OUT_OrderCancel() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_OrderCancel(String orderId, String cancelStatus, int remainingCancelledQty) {
        this();
        this.orderId = orderId;
        this.cancelStatus = cancelStatus;
        this.remainingCancelledQty = remainingCancelledQty;
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
    public String getCancelStatus() {
        return this.cancelStatus;
    }
    public void setCancelStatus(String cancelStatus) {
        this.cancelStatus = cancelStatus;
    }
    public int getRemainingCancelledQty() {
        return this.remainingCancelledQty;
    }
    public void setRemainingCancelledQty(int remainingCancelledQty) {
        this.remainingCancelledQty = remainingCancelledQty;
    }

    @Override
    public String toString() {
        return "MO_OUT_OrderCancel{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
