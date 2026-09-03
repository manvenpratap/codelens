package com.tcs.bancs.DP;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_MaturityInstruction
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_MaturityInstruction implements Serializable {

    private static final long serialVersionUID = 1L;

    private String depositId;
    private String action;
    private String beneficiaryAccount;
    private String messageCorrelationId;

    public MO_INP_MaturityInstruction() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_MaturityInstruction(String depositId, String action, String beneficiaryAccount) {
        this();
        this.depositId = depositId;
        this.action = action;
        this.beneficiaryAccount = beneficiaryAccount;
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
    public String getAction() {
        return this.action;
    }
    public void setAction(String action) {
        this.action = action;
    }
    public String getBeneficiaryAccount() {
        return this.beneficiaryAccount;
    }
    public void setBeneficiaryAccount(String beneficiaryAccount) {
        this.beneficiaryAccount = beneficiaryAccount;
    }

    @Override
    public String toString() {
        return "MO_INP_MaturityInstruction{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
