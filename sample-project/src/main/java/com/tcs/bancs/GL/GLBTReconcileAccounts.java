package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: GLBTReconcileAccounts
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class GLBTReconcileAccounts {

    private final GLDGPeriodGrabber dataGrabber;
    private final PeriodClosureService service;

    public GLBTReconcileAccounts() {
        this.dataGrabber = new GLDGPeriodGrabber();
        this.service = new PeriodClosureService();
    }

    public GLBTReconcileAccounts(GLDGPeriodGrabber dataGrabber, PeriodClosureService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: GLBTReconcileAccountsExecute
     */
    public MO_TrialBalanceItem GLBTReconcileAccountsExecute(MO_JournalLegItem req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "GLBTReconcileAccounts");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "GLBTReconcileAccounts");
        }

        // Step 2: Data Grabber state query
        FinancialPeriod entity = this.dataGrabber.fetchFinancialPeriodById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: GL -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "GLBTReconcileAccounts", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "GLBTReconcileAccounts", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("GLBTReconcileAccounts.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_TrialBalanceItem resp = new MO_TrialBalanceItem();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
