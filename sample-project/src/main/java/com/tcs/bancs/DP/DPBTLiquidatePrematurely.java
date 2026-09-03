package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: DPBTLiquidatePrematurely
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class DPBTLiquidatePrematurely {

    private final DPDGInterestLedgerGrabber dataGrabber;
    private final DepositInterestEngine service;

    public DPBTLiquidatePrematurely() {
        this.dataGrabber = new DPDGInterestLedgerGrabber();
        this.service = new DepositInterestEngine();
    }

    public DPBTLiquidatePrematurely(DPDGInterestLedgerGrabber dataGrabber, DepositInterestEngine service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: DPBTLiquidatePrematurelyExecute
     */
    public MO_OUT_PrematureWithdrawal DPBTLiquidatePrematurelyExecute(MO_INP_PrematureWithdrawal req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "DPBTLiquidatePrematurely");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "DPBTLiquidatePrematurely");
        }

        // Step 2: Data Grabber state query
        DepositInterestLedger entity = this.dataGrabber.fetchDepositInterestLedgerById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: DP -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "DPBTLiquidatePrematurely", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "DPBTLiquidatePrematurely", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("DPBTLiquidatePrematurely.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_PrematureWithdrawal resp = new MO_OUT_PrematureWithdrawal();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
