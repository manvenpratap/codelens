package com.tcs.bancs.CL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_SettlementInstruct
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_SettlementInstruct implements Serializable {

    private static final long serialVersionUID = 1L;

    private String instructionId;
    private String status;
    private String intendedDate;
    private String messageCorrelationId;

    public MO_OUT_SettlementInstruct() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_SettlementInstruct(String instructionId, String status, String intendedDate) {
        this();
        this.instructionId = instructionId;
        this.status = status;
        this.intendedDate = intendedDate;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getInstructionId() {
        return this.instructionId;
    }
    public void setInstructionId(String instructionId) {
        this.instructionId = instructionId;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public String getIntendedDate() {
        return this.intendedDate;
    }
    public void setIntendedDate(String intendedDate) {
        this.intendedDate = intendedDate;
    }

    @Override
    public String toString() {
        return "MO_OUT_SettlementInstruct{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
