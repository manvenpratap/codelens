package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: DPBTAccrueDepositInterest
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class DPBTAccrueDepositInterest {

    private final DPDGDepositGrabber dataGrabber;
    private final TaxDeductionService service;

    public DPBTAccrueDepositInterest() {
        this.dataGrabber = new DPDGDepositGrabber();
        this.service = new TaxDeductionService();
    }

    public DPBTAccrueDepositInterest(DPDGDepositGrabber dataGrabber, TaxDeductionService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: DPBTAccrueDepositInterestExecute
     */
    public MO_OUT_RateQuote DPBTAccrueDepositInterestExecute(MO_INP_RateQuote req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "DPBTAccrueDepositInterest");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "DPBTAccrueDepositInterest");
        }

        // Step 2: Data Grabber state query
        DepositContract entity = this.dataGrabber.fetchDepositContractById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: DP -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "DPBTAccrueDepositInterest", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "DPBTAccrueDepositInterest", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("DPBTAccrueDepositInterest.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_RateQuote resp = new MO_OUT_RateQuote();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
