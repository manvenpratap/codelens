package com.tcs.bancs.DP;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_TdsCertificate
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_TdsCertificate implements Serializable {

    private static final long serialVersionUID = 1L;

    private String depositId;
    private double grossInterest;
    private double taxDeducted;
    private String financialYear;
    private String messageCorrelationId;

    public MO_TdsCertificate() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_TdsCertificate(String depositId, double grossInterest, double taxDeducted, String financialYear) {
        this();
        this.depositId = depositId;
        this.grossInterest = grossInterest;
        this.taxDeducted = taxDeducted;
        this.financialYear = financialYear;
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
    public double getGrossInterest() {
        return this.grossInterest;
    }
    public void setGrossInterest(double grossInterest) {
        this.grossInterest = grossInterest;
    }
    public double getTaxDeducted() {
        return this.taxDeducted;
    }
    public void setTaxDeducted(double taxDeducted) {
        this.taxDeducted = taxDeducted;
    }
    public String getFinancialYear() {
        return this.financialYear;
    }
    public void setFinancialYear(String financialYear) {
        this.financialYear = financialYear;
    }

    @Override
    public String toString() {
        return "MO_TdsCertificate{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
