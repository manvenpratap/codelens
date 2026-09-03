package com.tcs.bancs.DP;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_DepositRolloverReport
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_DepositRolloverReport implements Serializable {

    private static final long serialVersionUID = 1L;

    private String depositId;
    private String newMaturityDate;
    private double newPrincipal;
    private String messageCorrelationId;

    public MO_DepositRolloverReport() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_DepositRolloverReport(String depositId, String newMaturityDate, double newPrincipal) {
        this();
        this.depositId = depositId;
        this.newMaturityDate = newMaturityDate;
        this.newPrincipal = newPrincipal;
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
    public String getNewMaturityDate() {
        return this.newMaturityDate;
    }
    public void setNewMaturityDate(String newMaturityDate) {
        this.newMaturityDate = newMaturityDate;
    }
    public double getNewPrincipal() {
        return this.newPrincipal;
    }
    public void setNewPrincipal(double newPrincipal) {
        this.newPrincipal = newPrincipal;
    }

    @Override
    public String toString() {
        return "MO_DepositRolloverReport{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
