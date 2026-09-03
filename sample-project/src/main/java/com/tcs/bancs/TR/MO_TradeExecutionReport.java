package com.tcs.bancs.TR;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_TradeExecutionReport
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_TradeExecutionReport implements Serializable {

    private static final long serialVersionUID = 1L;

    private String executionId;
    private String orderId;
    private String symbol;
    private int qty;
    private double price;
    private String side;
    private String messageCorrelationId;

    public MO_TradeExecutionReport() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_TradeExecutionReport(String executionId, String orderId, String symbol, int qty, double price, String side) {
        this();
        this.executionId = executionId;
        this.orderId = orderId;
        this.symbol = symbol;
        this.qty = qty;
        this.price = price;
        this.side = side;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getExecutionId() {
        return this.executionId;
    }
    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }
    public String getOrderId() {
        return this.orderId;
    }
    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
    public String getSymbol() {
        return this.symbol;
    }
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
    public int getQty() {
        return this.qty;
    }
    public void setQty(int qty) {
        this.qty = qty;
    }
    public double getPrice() {
        return this.price;
    }
    public void setPrice(double price) {
        this.price = price;
    }
    public String getSide() {
        return this.side;
    }
    public void setSide(String side) {
        this.side = side;
    }

    @Override
    public String toString() {
        return "MO_TradeExecutionReport{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
