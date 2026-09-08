package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: RiskAssessmentController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class RiskAssessmentController {

    private final MarketRiskService service;

    public RiskAssessmentController() {
        this.service = new MarketRiskService();
    }

    public RiskAssessmentController(MarketRiskService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: RKBTEvaluateLimit
     */
    public MO_OUT_LimitEvaluation RKBTEvaluateLimit(MO_INP_LimitEvaluation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "RiskAssessmentController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.RKBTEvaluateLimit(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: RKETCalculateVaR
     */
    public MO_OUT_LimitEvaluation RKETCalculateVaR(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "RiskAssessmentController", queryKey, "INQUIRY");
        return this.service.RKETCalculateVaR(queryKey);
    }

    /**
     * Generic execute request handler delegating to RKBTEvaluateLimit.
     */
    public MO_OUT_LimitEvaluation handleExecuteRequest(MO_INP_LimitEvaluation request) {
        return this.RKBTEvaluateLimit(request);
    }

    /**
     * Generic inquiry request handler delegating to RKETCalculateVaR.
     */
    public MO_OUT_LimitEvaluation handleInquiryRequest(String queryKey) {
        return this.RKETCalculateVaR(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
