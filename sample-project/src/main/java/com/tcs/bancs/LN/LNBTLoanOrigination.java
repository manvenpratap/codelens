package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: LNBTLoanOrigination
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class LNBTLoanOrigination {

    private final LNDGLoanGrabber dataGrabber;
    private final LoanOriginationService service;

    public LNBTLoanOrigination() {
        this.dataGrabber = new LNDGLoanGrabber();
        this.service = new LoanOriginationService();
    }

    public LNBTLoanOrigination(LNDGLoanGrabber dataGrabber, LoanOriginationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: LNBTLoanOriginationExecute
     */
    public MO_OUT_LoanApplication LNBTLoanOriginationExecute(MO_INP_LoanApplication req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "LNBTLoanOrigination");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "LNBTLoanOrigination");
        }

        // Step 2: Data Grabber state query
        Loan entity = this.dataGrabber.fetchLoanById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: LN -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "LNBTLoanOrigination", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "LNBTLoanOrigination", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("LNBTLoanOrigination.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_LoanApplication resp = new MO_OUT_LoanApplication();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
