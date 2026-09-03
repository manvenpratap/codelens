package com.tcs.bancs.LN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_ScheduleRestructure
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_ScheduleRestructure implements Serializable {

    private static final long serialVersionUID = 1L;

    private String loanId;
    private int additionalMonths;
    private double proposedRate;
    private String restructureReason;
    private String messageCorrelationId;

    public MO_INP_ScheduleRestructure() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_ScheduleRestructure(String loanId, int additionalMonths, double proposedRate, String restructureReason) {
        this();
        this.loanId = loanId;
        this.additionalMonths = additionalMonths;
        this.proposedRate = proposedRate;
        this.restructureReason = restructureReason;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getLoanId() {
        return this.loanId;
    }
    public void setLoanId(String loanId) {
        this.loanId = loanId;
    }
    public int getAdditionalMonths() {
        return this.additionalMonths;
    }
    public void setAdditionalMonths(int additionalMonths) {
        this.additionalMonths = additionalMonths;
    }
    public double getProposedRate() {
        return this.proposedRate;
    }
    public void setProposedRate(double proposedRate) {
        this.proposedRate = proposedRate;
    }
    public String getRestructureReason() {
        return this.restructureReason;
    }
    public void setRestructureReason(String restructureReason) {
        this.restructureReason = restructureReason;
    }

    @Override
    public String toString() {
        return "MO_INP_ScheduleRestructure{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
