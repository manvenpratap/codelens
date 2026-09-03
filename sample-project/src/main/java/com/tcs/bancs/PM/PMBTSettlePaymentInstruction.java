package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: PMBTSettlePaymentInstruction
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class PMBTSettlePaymentInstruction {

    private final PMDGMandateGrabber dataGrabber;
    private final PaymentValidationService service;

    public PMBTSettlePaymentInstruction() {
        this.dataGrabber = new PMDGMandateGrabber();
        this.service = new PaymentValidationService();
    }

    public PMBTSettlePaymentInstruction(PMDGMandateGrabber dataGrabber, PaymentValidationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: PMBTSettlePaymentInstructionExecute
     */
    public MO_OUT_PaymentCancellation PMBTSettlePaymentInstructionExecute(MO_INP_PaymentCancellation req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "PMBTSettlePaymentInstruction");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "PMBTSettlePaymentInstruction");
        }

        // Step 2: Data Grabber state query
        PaymentMandate entity = this.dataGrabber.fetchPaymentMandateById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: PM -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "PMBTSettlePaymentInstruction", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "PMBTSettlePaymentInstruction", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("PMBTSettlePaymentInstruction.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_PaymentCancellation resp = new MO_OUT_PaymentCancellation();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
