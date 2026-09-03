package com.tcs.bancs.MS;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_TransformMessage
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_TransformMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sourceProtocol;
    private String targetProtocol;
    private String payload;
    private String messageCorrelationId;

    public MO_INP_TransformMessage() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_TransformMessage(String sourceProtocol, String targetProtocol, String payload) {
        this();
        this.sourceProtocol = sourceProtocol;
        this.targetProtocol = targetProtocol;
        this.payload = payload;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getSourceProtocol() {
        return this.sourceProtocol;
    }
    public void setSourceProtocol(String sourceProtocol) {
        this.sourceProtocol = sourceProtocol;
    }
    public String getTargetProtocol() {
        return this.targetProtocol;
    }
    public void setTargetProtocol(String targetProtocol) {
        this.targetProtocol = targetProtocol;
    }
    public String getPayload() {
        return this.payload;
    }
    public void setPayload(String payload) {
        this.payload = payload;
    }

    @Override
    public String toString() {
        return "MO_INP_TransformMessage{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
