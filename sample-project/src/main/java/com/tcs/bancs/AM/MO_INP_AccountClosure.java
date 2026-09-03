package com.tcs.bancs.AM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_AccountClosure
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_AccountClosure implements Serializable {

    private static final long serialVersionUID = 1L;

    private String accountNumber;
    private String reason;
    private String payoutAccount;
    private boolean waiveCharges;
    private String messageCorrelationId;

    public MO_INP_AccountClosure() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_AccountClosure(String accountNumber, String reason, String payoutAccount, boolean waiveCharges) {
        this();
        this.accountNumber = accountNumber;
        this.reason = reason;
        this.payoutAccount = payoutAccount;
        this.waiveCharges = waiveCharges;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getAccountNumber() {
        return this.accountNumber;
    }
    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }
    public String getReason() {
        return this.reason;
    }
    public void setReason(String reason) {
        this.reason = reason;
    }
    public String getPayoutAccount() {
        return this.payoutAccount;
    }
    public void setPayoutAccount(String payoutAccount) {
        this.payoutAccount = payoutAccount;
    }
    public boolean getWaiveCharges() {
        return this.waiveCharges;
    }
    public void setWaiveCharges(boolean waiveCharges) {
        this.waiveCharges = waiveCharges;
    }

    @Override
    public String toString() {
        return "MO_INP_AccountClosure{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
