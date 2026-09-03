package com.tcs.bancs.AN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_PnLCalculation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_PnLCalculation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String portfolioId;
    private String date;
    private String methodology;
    private String messageCorrelationId;

    public MO_INP_PnLCalculation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_PnLCalculation(String portfolioId, String date, String methodology) {
        this();
        this.portfolioId = portfolioId;
        this.date = date;
        this.methodology = methodology;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getPortfolioId() {
        return this.portfolioId;
    }
    public void setPortfolioId(String portfolioId) {
        this.portfolioId = portfolioId;
    }
    public String getDate() {
        return this.date;
    }
    public void setDate(String date) {
        this.date = date;
    }
    public String getMethodology() {
        return this.methodology;
    }
    public void setMethodology(String methodology) {
        this.methodology = methodology;
    }

    @Override
    public String toString() {
        return "MO_INP_PnLCalculation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
