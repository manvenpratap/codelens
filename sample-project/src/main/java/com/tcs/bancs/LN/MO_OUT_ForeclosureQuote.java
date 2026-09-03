package com.tcs.bancs.LN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_ForeclosureQuote
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_ForeclosureQuote implements Serializable {

    private static final long serialVersionUID = 1L;

    private String loanId;
    private double principalOutstanding;
    private double interestTillDate;
    private double foreclosurePenalty;
    private double totalSettlement;
    private String messageCorrelationId;

    public MO_OUT_ForeclosureQuote() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_ForeclosureQuote(String loanId, double principalOutstanding, double interestTillDate, double foreclosurePenalty, double totalSettlement) {
        this();
        this.loanId = loanId;
        this.principalOutstanding = principalOutstanding;
        this.interestTillDate = interestTillDate;
        this.foreclosurePenalty = foreclosurePenalty;
        this.totalSettlement = totalSettlement;
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
    public double getPrincipalOutstanding() {
        return this.principalOutstanding;
    }
    public void setPrincipalOutstanding(double principalOutstanding) {
        this.principalOutstanding = principalOutstanding;
    }
    public double getInterestTillDate() {
        return this.interestTillDate;
    }
    public void setInterestTillDate(double interestTillDate) {
        this.interestTillDate = interestTillDate;
    }
    public double getForeclosurePenalty() {
        return this.foreclosurePenalty;
    }
    public void setForeclosurePenalty(double foreclosurePenalty) {
        this.foreclosurePenalty = foreclosurePenalty;
    }
    public double getTotalSettlement() {
        return this.totalSettlement;
    }
    public void setTotalSettlement(double totalSettlement) {
        this.totalSettlement = totalSettlement;
    }

    @Override
    public String toString() {
        return "MO_OUT_ForeclosureQuote{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
