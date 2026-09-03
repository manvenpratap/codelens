package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: PMBTAuthorizePayment
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class PMBTAuthorizePayment {

    private final PMDGRoutingGrabber dataGrabber;
    private final PaymentRoutingEngine service;

    public PMBTAuthorizePayment() {
        this.dataGrabber = new PMDGRoutingGrabber();
        this.service = new PaymentRoutingEngine();
    }

    public PMBTAuthorizePayment(PMDGRoutingGrabber dataGrabber, PaymentRoutingEngine service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: PMBTAuthorizePaymentExecute
     */
    public MO_OUT_PaymentStatusQuery PMBTAuthorizePaymentExecute(MO_INP_PaymentStatusQuery req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "PMBTAuthorizePayment");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "PMBTAuthorizePayment");
        }

        // Step 2: Data Grabber state query
        RoutingDirectory entity = this.dataGrabber.fetchRoutingDirectoryById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: PM -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "PMBTAuthorizePayment", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "PMBTAuthorizePayment", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("PMBTAuthorizePayment.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_PaymentStatusQuery resp = new MO_OUT_PaymentStatusQuery();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
