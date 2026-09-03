package com.tcs.bancs.CL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_DepositoryTransfer
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_DepositoryTransfer implements Serializable {

    private static final long serialVersionUID = 1L;

    private String transferId;
    private String status;
    private int unitsTransferred;
    private String messageCorrelationId;

    public MO_OUT_DepositoryTransfer() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_DepositoryTransfer(String transferId, String status, int unitsTransferred) {
        this();
        this.transferId = transferId;
        this.status = status;
        this.unitsTransferred = unitsTransferred;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getTransferId() {
        return this.transferId;
    }
    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public int getUnitsTransferred() {
        return this.unitsTransferred;
    }
    public void setUnitsTransferred(int unitsTransferred) {
        this.unitsTransferred = unitsTransferred;
    }

    @Override
    public String toString() {
        return "MO_OUT_DepositoryTransfer{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
