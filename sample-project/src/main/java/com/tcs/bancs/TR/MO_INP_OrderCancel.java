package com.tcs.bancs.TR;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_OrderCancel
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_OrderCancel implements Serializable {

    private static final long serialVersionUID = 1L;

    private String orderId;
    private String reason;
    private String operatorId;
    private String messageCorrelationId;

    public MO_INP_OrderCancel() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_OrderCancel(String orderId, String reason, String operatorId) {
        this();
        this.orderId = orderId;
        this.reason = reason;
        this.operatorId = operatorId;
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
    public String getReason() {
        return this.reason;
    }
    public void setReason(String reason) {
        this.reason = reason;
    }
    public String getOperatorId() {
        return this.operatorId;
    }
    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    @Override
    public String toString() {
        return "MO_INP_OrderCancel{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
