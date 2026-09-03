package com.tcs.bancs.PM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_PaymentChannelStatus
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_PaymentChannelStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    private String channel;
    private boolean isOperational;
    private double throughputTps;
    private String messageCorrelationId;

    public MO_PaymentChannelStatus() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_PaymentChannelStatus(String channel, boolean isOperational, double throughputTps) {
        this();
        this.channel = channel;
        this.isOperational = isOperational;
        this.throughputTps = throughputTps;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getChannel() {
        return this.channel;
    }
    public void setChannel(String channel) {
        this.channel = channel;
    }
    public boolean getIsOperational() {
        return this.isOperational;
    }
    public void setIsOperational(boolean isOperational) {
        this.isOperational = isOperational;
    }
    public double getThroughputTps() {
        return this.throughputTps;
    }
    public void setThroughputTps(double throughputTps) {
        this.throughputTps = throughputTps;
    }

    @Override
    public String toString() {
        return "MO_PaymentChannelStatus{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
