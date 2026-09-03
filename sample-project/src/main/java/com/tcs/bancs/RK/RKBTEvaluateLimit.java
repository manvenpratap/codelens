package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: RKBTEvaluateLimit
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class RKBTEvaluateLimit {

    private final RKDGRiskGrabber dataGrabber;
    private final MarketRiskService service;

    public RKBTEvaluateLimit() {
        this.dataGrabber = new RKDGRiskGrabber();
        this.service = new MarketRiskService();
    }

    public RKBTEvaluateLimit(RKDGRiskGrabber dataGrabber, MarketRiskService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: RKBTEvaluateLimitExecute
     */
    public MO_OUT_LimitEvaluation RKBTEvaluateLimitExecute(MO_INP_LimitEvaluation req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "RKBTEvaluateLimit");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "RKBTEvaluateLimit");
        }

        // Step 2: Data Grabber state query
        RiskExposure entity = this.dataGrabber.fetchRiskExposureById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: RK -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "RKBTEvaluateLimit", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "RKBTEvaluateLimit", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("RKBTEvaluateLimit.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_LimitEvaluation resp = new MO_OUT_LimitEvaluation();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
