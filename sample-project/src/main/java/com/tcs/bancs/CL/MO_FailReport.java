package com.tcs.bancs.CL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_FailReport
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_FailReport implements Serializable {

    private static final long serialVersionUID = 1L;

    private String failId;
    private String instructionId;
    private String reason;
    private double penalty;
    private String messageCorrelationId;

    public MO_FailReport() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_FailReport(String failId, String instructionId, String reason, double penalty) {
        this();
        this.failId = failId;
        this.instructionId = instructionId;
        this.reason = reason;
        this.penalty = penalty;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getFailId() {
        return this.failId;
    }
    public void setFailId(String failId) {
        this.failId = failId;
    }
    public String getInstructionId() {
        return this.instructionId;
    }
    public void setInstructionId(String instructionId) {
        this.instructionId = instructionId;
    }
    public String getReason() {
        return this.reason;
    }
    public void setReason(String reason) {
        this.reason = reason;
    }
    public double getPenalty() {
        return this.penalty;
    }
    public void setPenalty(double penalty) {
        this.penalty = penalty;
    }

    @Override
    public String toString() {
        return "MO_FailReport{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
