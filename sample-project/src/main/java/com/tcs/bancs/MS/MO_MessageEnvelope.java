package com.tcs.bancs.MS;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_MessageEnvelope
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_MessageEnvelope implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String protocol;
    private String format;
    private String content;
    private String messageCorrelationId;

    public MO_MessageEnvelope() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_MessageEnvelope(String id, String protocol, String format, String content) {
        this();
        this.id = id;
        this.protocol = protocol;
        this.format = format;
        this.content = content;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getId() {
        return this.id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public String getProtocol() {
        return this.protocol;
    }
    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }
    public String getFormat() {
        return this.format;
    }
    public void setFormat(String format) {
        this.format = format;
    }
    public String getContent() {
        return this.content;
    }
    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public String toString() {
        return "MO_MessageEnvelope{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
