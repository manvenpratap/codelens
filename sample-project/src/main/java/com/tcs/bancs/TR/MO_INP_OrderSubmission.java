package com.tcs.bancs.TR;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_OrderSubmission
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_OrderSubmission implements Serializable {

    private static final long serialVersionUID = 1L;

    private String portfolioId;
    private String symbol;
    private String orderSide;
    private int quantity;
    private double price;
    private String orderType;
    private String messageCorrelationId;

    public MO_INP_OrderSubmission() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_OrderSubmission(String portfolioId, String symbol, String orderSide, int quantity, double price, String orderType) {
        this();
        this.portfolioId = portfolioId;
        this.symbol = symbol;
        this.orderSide = orderSide;
        this.quantity = quantity;
        this.price = price;
        this.orderType = orderType;
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
    public String getSymbol() {
        return this.symbol;
    }
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
    public String getOrderSide() {
        return this.orderSide;
    }
    public void setOrderSide(String orderSide) {
        this.orderSide = orderSide;
    }
    public int getQuantity() {
        return this.quantity;
    }
    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
    public double getPrice() {
        return this.price;
    }
    public void setPrice(double price) {
        this.price = price;
    }
    public String getOrderType() {
        return this.orderType;
    }
    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }

    @Override
    public String toString() {
        return "MO_INP_OrderSubmission{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
