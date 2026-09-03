package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.LN.*;

/**
 * TCS BaNCS Business Transaction: SCBTIssueMarginCall
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class SCBTIssueMarginCall {

    private final SCDGValuationGrabber dataGrabber;
    private final MarginCallManagementService service;

    public SCBTIssueMarginCall() {
        this.dataGrabber = new SCDGValuationGrabber();
        this.service = new MarginCallManagementService();
    }

    public SCBTIssueMarginCall(SCDGValuationGrabber dataGrabber, MarginCallManagementService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: SCBTIssueMarginCallExecute
     */
    public MO_OUT_MarginCallIssue SCBTIssueMarginCallExecute(MO_INP_MarginCallIssue req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "SCBTIssueMarginCall");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "SCBTIssueMarginCall");
        }

        // Step 2: Data Grabber state query
        ValuationAppraisalReport entity = this.dataGrabber.fetchValuationAppraisalReportById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: SC -> LN
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "SCBTIssueMarginCall", req.getMessageCorrelationId(), "LN");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "SCBTIssueMarginCall", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("SCBTIssueMarginCall.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_MarginCallIssue resp = new MO_OUT_MarginCallIssue();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
