package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: PMBTExecuteDirectDebit
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class PMBTExecuteDirectDebit {

    private final PMDGPaymentGrabber dataGrabber;
    private final FeeDeductionService service;

    public PMBTExecuteDirectDebit() {
        this.dataGrabber = new PMDGPaymentGrabber();
        this.service = new FeeDeductionService();
    }

    public PMBTExecuteDirectDebit(PMDGPaymentGrabber dataGrabber, FeeDeductionService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: PMBTExecuteDirectDebitExecute
     */
    public MO_OUT_DirectDebitBatch PMBTExecuteDirectDebitExecute(MO_INP_DirectDebitBatch req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "PMBTExecuteDirectDebit");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "PMBTExecuteDirectDebit");
        }

        // Step 2: Data Grabber state query
        PaymentTransaction entity = this.dataGrabber.fetchPaymentTransactionById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: PM -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "PMBTExecuteDirectDebit", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "PMBTExecuteDirectDebit", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("PMBTExecuteDirectDebit.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_DirectDebitBatch resp = new MO_OUT_DirectDebitBatch();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
