package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: RiskAssessmentController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class RiskAssessmentController {

    private final RKBTEvaluateLimit businessTransaction;
    private final RKETCalculateVaR elementaryTransaction;

    public RiskAssessmentController() {
        this.businessTransaction = new RKBTEvaluateLimit();
        this.elementaryTransaction = new RKETCalculateVaR();
    }

    public RiskAssessmentController(RKBTEvaluateLimit bt, RKETCalculateVaR et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_LimitEvaluation handleExecuteRequest(MO_INP_LimitEvaluation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "RiskAssessmentController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.RKBTEvaluateLimitExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_LimitEvaluation handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "RiskAssessmentController", queryKey, "INQUIRY");
        return this.elementaryTransaction.RKETCalculateVaRFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
