package com.tcs.bancs.AM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_BalanceInquiry
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_BalanceInquiry implements Serializable {

    private static final long serialVersionUID = 1L;

    private String accountNumber;
    private boolean includeHolds;
    private String channel;
    private String messageCorrelationId;

    public MO_INP_BalanceInquiry() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_BalanceInquiry(String accountNumber, boolean includeHolds, String channel) {
        this();
        this.accountNumber = accountNumber;
        this.includeHolds = includeHolds;
        this.channel = channel;
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
    public boolean getIncludeHolds() {
        return this.includeHolds;
    }
    public void setIncludeHolds(boolean includeHolds) {
        this.includeHolds = includeHolds;
    }
    public String getChannel() {
        return this.channel;
    }
    public void setChannel(String channel) {
        this.channel = channel;
    }

    @Override
    public String toString() {
        return "MO_INP_BalanceInquiry{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
