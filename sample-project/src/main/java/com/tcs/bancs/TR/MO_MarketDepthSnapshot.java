package com.tcs.bancs.TR;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_MarketDepthSnapshot
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_MarketDepthSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private String symbol;
    private double bestBid;
    private double bestAsk;
    private int bidVolume;
    private int askVolume;
    private String messageCorrelationId;

    public MO_MarketDepthSnapshot() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_MarketDepthSnapshot(String symbol, double bestBid, double bestAsk, int bidVolume, int askVolume) {
        this();
        this.symbol = symbol;
        this.bestBid = bestBid;
        this.bestAsk = bestAsk;
        this.bidVolume = bidVolume;
        this.askVolume = askVolume;
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
    public double getBestBid() {
        return this.bestBid;
    }
    public void setBestBid(double bestBid) {
        this.bestBid = bestBid;
    }
    public double getBestAsk() {
        return this.bestAsk;
    }
    public void setBestAsk(double bestAsk) {
        this.bestAsk = bestAsk;
    }
    public int getBidVolume() {
        return this.bidVolume;
    }
    public void setBidVolume(int bidVolume) {
        this.bidVolume = bidVolume;
    }
    public int getAskVolume() {
        return this.askVolume;
    }
    public void setAskVolume(int askVolume) {
        this.askVolume = askVolume;
    }

    @Override
    public String toString() {
        return "MO_MarketDepthSnapshot{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
