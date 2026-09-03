package com.tcs.bancs.DP;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_PrematureWithdrawal
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_PrematureWithdrawal implements Serializable {

    private static final long serialVersionUID = 1L;

    private String depositId;
    private String reason;
    private String payoutAccount;
    private String messageCorrelationId;

    public MO_INP_PrematureWithdrawal() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_PrematureWithdrawal(String depositId, String reason, String payoutAccount) {
        this();
        this.depositId = depositId;
        this.reason = reason;
        this.payoutAccount = payoutAccount;
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

    @Override
    public String toString() {
        return "MO_INP_PrematureWithdrawal{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
