package com.tcs.bancs.LN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_ScheduleRestructure
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_ScheduleRestructure implements Serializable {

    private static final long serialVersionUID = 1L;

    private String loanId;
    private String status;
    private double newEmi;
    private int newTotalTenure;
    private String messageCorrelationId;

    public MO_OUT_ScheduleRestructure() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_ScheduleRestructure(String loanId, String status, double newEmi, int newTotalTenure) {
        this();
        this.loanId = loanId;
        this.status = status;
        this.newEmi = newEmi;
        this.newTotalTenure = newTotalTenure;
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
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public double getNewEmi() {
        return this.newEmi;
    }
    public void setNewEmi(double newEmi) {
        this.newEmi = newEmi;
    }
    public int getNewTotalTenure() {
        return this.newTotalTenure;
    }
    public void setNewTotalTenure(int newTotalTenure) {
        this.newTotalTenure = newTotalTenure;
    }

    @Override
    public String toString() {
        return "MO_OUT_ScheduleRestructure{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
