package com.tcs.bancs.PM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_PaymentInitiation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_PaymentInitiation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String paymentId;
    private String endToEndId;
    private String status;
    private double fee;
    private long timestamp;
    private String messageCorrelationId;

    public MO_OUT_PaymentInitiation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_PaymentInitiation(String paymentId, String endToEndId, String status, double fee, long timestamp) {
        this();
        this.paymentId = paymentId;
        this.endToEndId = endToEndId;
        this.status = status;
        this.fee = fee;
        this.timestamp = timestamp;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getPaymentId() {
        return this.paymentId;
    }
    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }
    public String getEndToEndId() {
        return this.endToEndId;
    }
    public void setEndToEndId(String endToEndId) {
        this.endToEndId = endToEndId;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public double getFee() {
        return this.fee;
    }
    public void setFee(double fee) {
        this.fee = fee;
    }
    public long getTimestamp() {
        return this.timestamp;
    }
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "MO_OUT_PaymentInitiation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
