package com.tcs.bancs.RK;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_AmlScreening
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_AmlScreening implements Serializable {

    private static final long serialVersionUID = 1L;

    private String screeningRef;
    private boolean flagged;
    private double riskScore;
    private String matchedRule;
    private String messageCorrelationId;

    public MO_OUT_AmlScreening() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_AmlScreening(String screeningRef, boolean flagged, double riskScore, String matchedRule) {
        this();
        this.screeningRef = screeningRef;
        this.flagged = flagged;
        this.riskScore = riskScore;
        this.matchedRule = matchedRule;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getScreeningRef() {
        return this.screeningRef;
    }
    public void setScreeningRef(String screeningRef) {
        this.screeningRef = screeningRef;
    }
    public boolean getFlagged() {
        return this.flagged;
    }
    public void setFlagged(boolean flagged) {
        this.flagged = flagged;
    }
    public double getRiskScore() {
        return this.riskScore;
    }
    public void setRiskScore(double riskScore) {
        this.riskScore = riskScore;
    }
    public String getMatchedRule() {
        return this.matchedRule;
    }
    public void setMatchedRule(String matchedRule) {
        this.matchedRule = matchedRule;
    }

    @Override
    public String toString() {
        return "MO_OUT_AmlScreening{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
