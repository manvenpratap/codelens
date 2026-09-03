package com.tcs.bancs.MS;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_FixNewOrderSingle
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_FixNewOrderSingle implements Serializable {

    private static final long serialVersionUID = 1L;

    private String clOrdId;
    private String symbol;
    private String side;
    private int orderQty;
    private double price;
    private String messageCorrelationId;

    public MO_INP_FixNewOrderSingle() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_FixNewOrderSingle(String clOrdId, String symbol, String side, int orderQty, double price) {
        this();
        this.clOrdId = clOrdId;
        this.symbol = symbol;
        this.side = side;
        this.orderQty = orderQty;
        this.price = price;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getClOrdId() {
        return this.clOrdId;
    }
    public void setClOrdId(String clOrdId) {
        this.clOrdId = clOrdId;
    }
    public String getSymbol() {
        return this.symbol;
    }
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
    public String getSide() {
        return this.side;
    }
    public void setSide(String side) {
        this.side = side;
    }
    public int getOrderQty() {
        return this.orderQty;
    }
    public void setOrderQty(int orderQty) {
        this.orderQty = orderQty;
    }
    public double getPrice() {
        return this.price;
    }
    public void setPrice(double price) {
        this.price = price;
    }

    @Override
    public String toString() {
        return "MO_INP_FixNewOrderSingle{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
