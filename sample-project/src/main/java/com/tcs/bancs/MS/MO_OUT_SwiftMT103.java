package com.tcs.bancs.MS;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_SwiftMT103
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_SwiftMT103 implements Serializable {

    private static final long serialVersionUID = 1L;

    private String murReference;
    private String status;
    private String ackNack;
    private long sendTime;
    private String messageCorrelationId;

    public MO_OUT_SwiftMT103() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_SwiftMT103(String murReference, String status, String ackNack, long sendTime) {
        this();
        this.murReference = murReference;
        this.status = status;
        this.ackNack = ackNack;
        this.sendTime = sendTime;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getMurReference() {
        return this.murReference;
    }
    public void setMurReference(String murReference) {
        this.murReference = murReference;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public String getAckNack() {
        return this.ackNack;
    }
    public void setAckNack(String ackNack) {
        this.ackNack = ackNack;
    }
    public long getSendTime() {
        return this.sendTime;
    }
    public void setSendTime(long sendTime) {
        this.sendTime = sendTime;
    }

    @Override
    public String toString() {
        return "MO_OUT_SwiftMT103{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
