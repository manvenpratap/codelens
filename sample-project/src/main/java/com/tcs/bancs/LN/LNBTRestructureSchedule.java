package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: LNBTRestructureSchedule
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class LNBTRestructureSchedule {

    private final LNDGLoanGrabber dataGrabber;
    private final InterestRebateService service;

    public LNBTRestructureSchedule() {
        this.dataGrabber = new LNDGLoanGrabber();
        this.service = new InterestRebateService();
    }

    public LNBTRestructureSchedule(LNDGLoanGrabber dataGrabber, InterestRebateService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: LNBTRestructureScheduleExecute
     */
    public MO_OUT_ForeclosureQuote LNBTRestructureScheduleExecute(MO_INP_ForeclosureQuote req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "LNBTRestructureSchedule");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "LNBTRestructureSchedule");
        }

        // Step 2: Data Grabber state query
        Loan entity = this.dataGrabber.fetchLoanById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: LN -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "LNBTRestructureSchedule", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "LNBTRestructureSchedule", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("LNBTRestructureSchedule.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_ForeclosureQuote resp = new MO_OUT_ForeclosureQuote();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
