package com.tcs.bancs.PM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_PaymentCancellation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_PaymentCancellation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String paymentId;
    private String cancelStatus;
    private boolean reversalInitiated;
    private String messageCorrelationId;

    public MO_OUT_PaymentCancellation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_PaymentCancellation(String paymentId, String cancelStatus, boolean reversalInitiated) {
        this();
        this.paymentId = paymentId;
        this.cancelStatus = cancelStatus;
        this.reversalInitiated = reversalInitiated;
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
    public String getCancelStatus() {
        return this.cancelStatus;
    }
    public void setCancelStatus(String cancelStatus) {
        this.cancelStatus = cancelStatus;
    }
    public boolean getReversalInitiated() {
        return this.reversalInitiated;
    }
    public void setReversalInitiated(boolean reversalInitiated) {
        this.reversalInitiated = reversalInitiated;
    }

    @Override
    public String toString() {
        return "MO_OUT_PaymentCancellation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
