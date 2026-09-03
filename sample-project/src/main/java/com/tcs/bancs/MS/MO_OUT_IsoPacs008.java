package com.tcs.bancs.MS;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_IsoPacs008
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_IsoPacs008 implements Serializable {

    private static final long serialVersionUID = 1L;

    private String txId;
    private String clearingStatus;
    private String reasonCode;
    private String messageCorrelationId;

    public MO_OUT_IsoPacs008() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_IsoPacs008(String txId, String clearingStatus, String reasonCode) {
        this();
        this.txId = txId;
        this.clearingStatus = clearingStatus;
        this.reasonCode = reasonCode;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getTxId() {
        return this.txId;
    }
    public void setTxId(String txId) {
        this.txId = txId;
    }
    public String getClearingStatus() {
        return this.clearingStatus;
    }
    public void setClearingStatus(String clearingStatus) {
        this.clearingStatus = clearingStatus;
    }
    public String getReasonCode() {
        return this.reasonCode;
    }
    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    @Override
    public String toString() {
        return "MO_OUT_IsoPacs008{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
