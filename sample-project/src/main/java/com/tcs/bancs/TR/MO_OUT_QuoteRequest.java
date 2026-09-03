package com.tcs.bancs.TR;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_QuoteRequest
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_QuoteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String quoteId;
    private String symbol;
    private double bidPrice;
    private double askPrice;
    private long validUntil;
    private String messageCorrelationId;

    public MO_OUT_QuoteRequest() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_QuoteRequest(String quoteId, String symbol, double bidPrice, double askPrice, long validUntil) {
        this();
        this.quoteId = quoteId;
        this.symbol = symbol;
        this.bidPrice = bidPrice;
        this.askPrice = askPrice;
        this.validUntil = validUntil;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getQuoteId() {
        return this.quoteId;
    }
    public void setQuoteId(String quoteId) {
        this.quoteId = quoteId;
    }
    public String getSymbol() {
        return this.symbol;
    }
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
    public double getBidPrice() {
        return this.bidPrice;
    }
    public void setBidPrice(double bidPrice) {
        this.bidPrice = bidPrice;
    }
    public double getAskPrice() {
        return this.askPrice;
    }
    public void setAskPrice(double askPrice) {
        this.askPrice = askPrice;
    }
    public long getValidUntil() {
        return this.validUntil;
    }
    public void setValidUntil(long validUntil) {
        this.validUntil = validUntil;
    }

    @Override
    public String toString() {
        return "MO_OUT_QuoteRequest{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
