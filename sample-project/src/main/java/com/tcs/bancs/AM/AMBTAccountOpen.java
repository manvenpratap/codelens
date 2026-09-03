package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.GL.*;

/**
 * TCS BaNCS Business Transaction: AMBTAccountOpen
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class AMBTAccountOpen {

    private final AMDGAccountGrabber dataGrabber;
    private final AccountService service;

    public AMBTAccountOpen() {
        this.dataGrabber = new AMDGAccountGrabber();
        this.service = new AccountService();
    }

    public AMBTAccountOpen(AMDGAccountGrabber dataGrabber, AccountService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: AMBTAccountOpenExecute
     */
    public MO_OUT_AccountOpen AMBTAccountOpenExecute(MO_INP_AccountOpen req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "AMBTAccountOpen");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "AMBTAccountOpen");
        }

        // Step 2: Data Grabber state query
        Account entity = this.dataGrabber.fetchAccountById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: AM -> GL
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "AMBTAccountOpen", req.getMessageCorrelationId(), "GL");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "AMBTAccountOpen", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("AMBTAccountOpen.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_AccountOpen resp = new MO_OUT_AccountOpen();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
