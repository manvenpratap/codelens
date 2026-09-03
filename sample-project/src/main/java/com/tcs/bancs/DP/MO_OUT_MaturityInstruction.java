package com.tcs.bancs.DP;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_MaturityInstruction
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_MaturityInstruction implements Serializable {

    private static final long serialVersionUID = 1L;

    private String depositId;
    private String updatedAction;
    private boolean confirmed;
    private String messageCorrelationId;

    public MO_OUT_MaturityInstruction() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_MaturityInstruction(String depositId, String updatedAction, boolean confirmed) {
        this();
        this.depositId = depositId;
        this.updatedAction = updatedAction;
        this.confirmed = confirmed;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getDepositId() {
        return this.depositId;
    }
    public void setDepositId(String depositId) {
        this.depositId = depositId;
    }
    public String getUpdatedAction() {
        return this.updatedAction;
    }
    public void setUpdatedAction(String updatedAction) {
        this.updatedAction = updatedAction;
    }
    public boolean getConfirmed() {
        return this.confirmed;
    }
    public void setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }

    @Override
    public String toString() {
        return "MO_OUT_MaturityInstruction{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
