package com.tcs.bancs.TR;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_PortfolioPosition
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_PortfolioPosition implements Serializable {

    private static final long serialVersionUID = 1L;

    private String portfolioId;
    private String symbol;
    private int quantity;
    private double marketValue;
    private double pnl;
    private String messageCorrelationId;

    public MO_PortfolioPosition() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_PortfolioPosition(String portfolioId, String symbol, int quantity, double marketValue, double pnl) {
        this();
        this.portfolioId = portfolioId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.marketValue = marketValue;
        this.pnl = pnl;
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
    public int getQuantity() {
        return this.quantity;
    }
    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
    public double getMarketValue() {
        return this.marketValue;
    }
    public void setMarketValue(double marketValue) {
        this.marketValue = marketValue;
    }
    public double getPnl() {
        return this.pnl;
    }
    public void setPnl(double pnl) {
        this.pnl = pnl;
    }

    @Override
    public String toString() {
        return "MO_PortfolioPosition{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
