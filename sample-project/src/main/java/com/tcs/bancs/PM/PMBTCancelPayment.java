package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: PMBTCancelPayment
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class PMBTCancelPayment {

    private final PMDGClearingQueueGrabber dataGrabber;
    private final DirectDebitMandateService service;

    public PMBTCancelPayment() {
        this.dataGrabber = new PMDGClearingQueueGrabber();
        this.service = new DirectDebitMandateService();
    }

    public PMBTCancelPayment(PMDGClearingQueueGrabber dataGrabber, DirectDebitMandateService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: PMBTCancelPaymentExecute
     */
    public MO_MandateDetails PMBTCancelPaymentExecute(MO_PaymentRoutingPath req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "PMBTCancelPayment");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "PMBTCancelPayment");
        }

        // Step 2: Data Grabber state query
        ClearingReturnRecord entity = this.dataGrabber.fetchClearingReturnRecordById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: PM -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "PMBTCancelPayment", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "PMBTCancelPayment", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("PMBTCancelPayment.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_MandateDetails resp = new MO_MandateDetails();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
