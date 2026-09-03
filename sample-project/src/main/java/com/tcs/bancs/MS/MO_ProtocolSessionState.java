package com.tcs.bancs.MS;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_ProtocolSessionState
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_ProtocolSessionState implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sessionId;
    private String protocol;
    private boolean connected;
    private int inSeq;
    private int outSeq;
    private String messageCorrelationId;

    public MO_ProtocolSessionState() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_ProtocolSessionState(String sessionId, String protocol, boolean connected, int inSeq, int outSeq) {
        this();
        this.sessionId = sessionId;
        this.protocol = protocol;
        this.connected = connected;
        this.inSeq = inSeq;
        this.outSeq = outSeq;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getSessionId() {
        return this.sessionId;
    }
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
    public String getProtocol() {
        return this.protocol;
    }
    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }
    public boolean getConnected() {
        return this.connected;
    }
    public void setConnected(boolean connected) {
        this.connected = connected;
    }
    public int getInSeq() {
        return this.inSeq;
    }
    public void setInSeq(int inSeq) {
        this.inSeq = inSeq;
    }
    public int getOutSeq() {
        return this.outSeq;
    }
    public void setOutSeq(int outSeq) {
        this.outSeq = outSeq;
    }

    @Override
    public String toString() {
        return "MO_ProtocolSessionState{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
