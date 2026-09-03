package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: LNBTDisburseLoan
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class LNBTDisburseLoan {

    private final LNDGScheduleGrabber dataGrabber;
    private final AmortizationCalculationService service;

    public LNBTDisburseLoan() {
        this.dataGrabber = new LNDGScheduleGrabber();
        this.service = new AmortizationCalculationService();
    }

    public LNBTDisburseLoan(LNDGScheduleGrabber dataGrabber, AmortizationCalculationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: LNBTDisburseLoanExecute
     */
    public MO_OUT_LoanDisbursement LNBTDisburseLoanExecute(MO_INP_LoanDisbursement req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "LNBTDisburseLoan");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "LNBTDisburseLoan");
        }

        // Step 2: Data Grabber state query
        LoanRepaymentSchedule entity = this.dataGrabber.fetchLoanRepaymentScheduleById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: LN -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "LNBTDisburseLoan", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "LNBTDisburseLoan", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("LNBTDisburseLoan.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_LoanDisbursement resp = new MO_OUT_LoanDisbursement();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
