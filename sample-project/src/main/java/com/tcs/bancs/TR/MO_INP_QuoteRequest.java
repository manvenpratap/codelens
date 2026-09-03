package com.tcs.bancs.TR;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_QuoteRequest
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_QuoteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String symbol;
    private int size;
    private String side;
    private String messageCorrelationId;

    public MO_INP_QuoteRequest() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_QuoteRequest(String symbol, int size, String side) {
        this();
        this.symbol = symbol;
        this.size = size;
        this.side = side;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getSymbol() {
        return this.symbol;
    }
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
    public int getSize() {
        return this.size;
    }
    public void setSize(int size) {
        this.size = size;
    }
    public String getSide() {
        return this.side;
    }
    public void setSide(String side) {
        this.side = side;
    }

    @Override
    public String toString() {
        return "MO_INP_QuoteRequest{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
