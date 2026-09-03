package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: GLBTCloseFiscalPeriod
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class GLBTCloseFiscalPeriod {

    private final GLDGTrialBalanceGrabber dataGrabber;
    private final TrialBalanceCalculationService service;

    public GLBTCloseFiscalPeriod() {
        this.dataGrabber = new GLDGTrialBalanceGrabber();
        this.service = new TrialBalanceCalculationService();
    }

    public GLBTCloseFiscalPeriod(GLDGTrialBalanceGrabber dataGrabber, TrialBalanceCalculationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: GLBTCloseFiscalPeriodExecute
     */
    public MO_OUT_PeriodClose GLBTCloseFiscalPeriodExecute(MO_INP_PeriodClose req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "GLBTCloseFiscalPeriod");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "GLBTCloseFiscalPeriod");
        }

        // Step 2: Data Grabber state query
        JournalPostingLeg entity = this.dataGrabber.fetchJournalPostingLegById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: GL -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "GLBTCloseFiscalPeriod", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "GLBTCloseFiscalPeriod", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("GLBTCloseFiscalPeriod.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_PeriodClose resp = new MO_OUT_PeriodClose();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
