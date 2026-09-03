package com.tcs.bancs.GL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_TrialBalanceQuery
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_TrialBalanceQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private int accountCount;
    private double totalDebit;
    private double totalCredit;
    private boolean isBalanced;
    private String messageCorrelationId;

    public MO_OUT_TrialBalanceQuery() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_TrialBalanceQuery(int accountCount, double totalDebit, double totalCredit, boolean isBalanced) {
        this();
        this.accountCount = accountCount;
        this.totalDebit = totalDebit;
        this.totalCredit = totalCredit;
        this.isBalanced = isBalanced;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public int getAccountCount() {
        return this.accountCount;
    }
    public void setAccountCount(int accountCount) {
        this.accountCount = accountCount;
    }
    public double getTotalDebit() {
        return this.totalDebit;
    }
    public void setTotalDebit(double totalDebit) {
        this.totalDebit = totalDebit;
    }
    public double getTotalCredit() {
        return this.totalCredit;
    }
    public void setTotalCredit(double totalCredit) {
        this.totalCredit = totalCredit;
    }
    public boolean getIsBalanced() {
        return this.isBalanced;
    }
    public void setIsBalanced(boolean isBalanced) {
        this.isBalanced = isBalanced;
    }

    @Override
    public String toString() {
        return "MO_OUT_TrialBalanceQuery{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
