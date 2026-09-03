package com.tcs.bancs.LN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_ForeclosureQuote
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_ForeclosureQuote implements Serializable {

    private static final long serialVersionUID = 1L;

    private String loanId;
    private String intendedDate;
    private String messageCorrelationId;

    public MO_INP_ForeclosureQuote() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_ForeclosureQuote(String loanId, String intendedDate) {
        this();
        this.loanId = loanId;
        this.intendedDate = intendedDate;
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
    public String getIntendedDate() {
        return this.intendedDate;
    }
    public void setIntendedDate(String intendedDate) {
        this.intendedDate = intendedDate;
    }

    @Override
    public String toString() {
        return "MO_INP_ForeclosureQuote{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
