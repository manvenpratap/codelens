package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.GL.*;

/**
 * TCS BaNCS Business Transaction: AMBTHoldFunds
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class AMBTHoldFunds {

    private final AMDGAccountGrabber dataGrabber;
    private final StatementGenerationService service;

    public AMBTHoldFunds() {
        this.dataGrabber = new AMDGAccountGrabber();
        this.service = new StatementGenerationService();
    }

    public AMBTHoldFunds(AMDGAccountGrabber dataGrabber, StatementGenerationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: AMBTHoldFundsExecute
     */
    public MO_OUT_HoldFunds AMBTHoldFundsExecute(MO_INP_HoldFunds req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "AMBTHoldFunds");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "AMBTHoldFunds");
        }

        // Step 2: Data Grabber state query
        Account entity = this.dataGrabber.fetchAccountById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: AM -> GL
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "AMBTHoldFunds", req.getMessageCorrelationId(), "GL");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "AMBTHoldFunds", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("AMBTHoldFunds.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_HoldFunds resp = new MO_OUT_HoldFunds();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
