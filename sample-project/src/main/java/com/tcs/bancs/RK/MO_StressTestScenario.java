package com.tcs.bancs.RK;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_StressTestScenario
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_StressTestScenario implements Serializable {

    private static final long serialVersionUID = 1L;

    private String scenarioId;
    private String name;
    private double shockPct;
    private double marketImpact;
    private String messageCorrelationId;

    public MO_StressTestScenario() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_StressTestScenario(String scenarioId, String name, double shockPct, double marketImpact) {
        this();
        this.scenarioId = scenarioId;
        this.name = name;
        this.shockPct = shockPct;
        this.marketImpact = marketImpact;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getScenarioId() {
        return this.scenarioId;
    }
    public void setScenarioId(String scenarioId) {
        this.scenarioId = scenarioId;
    }
    public String getName() {
        return this.name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public double getShockPct() {
        return this.shockPct;
    }
    public void setShockPct(double shockPct) {
        this.shockPct = shockPct;
    }
    public double getMarketImpact() {
        return this.marketImpact;
    }
    public void setMarketImpact(double marketImpact) {
        this.marketImpact = marketImpact;
    }

    @Override
    public String toString() {
        return "MO_StressTestScenario{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
