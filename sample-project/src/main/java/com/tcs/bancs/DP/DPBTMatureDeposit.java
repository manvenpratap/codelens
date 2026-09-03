package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: DPBTMatureDeposit
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class DPBTMatureDeposit {

    private final DPDGPenaltyRuleGrabber dataGrabber;
    private final PrematureLiquidationService service;

    public DPBTMatureDeposit() {
        this.dataGrabber = new DPDGPenaltyRuleGrabber();
        this.service = new PrematureLiquidationService();
    }

    public DPBTMatureDeposit(DPDGPenaltyRuleGrabber dataGrabber, PrematureLiquidationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: DPBTMatureDepositExecute
     */
    public MO_OUT_MaturityInstruction DPBTMatureDepositExecute(MO_INP_MaturityInstruction req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "DPBTMatureDeposit");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "DPBTMatureDeposit");
        }

        // Step 2: Data Grabber state query
        PrematurePenaltyRule entity = this.dataGrabber.fetchPrematurePenaltyRuleById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: DP -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "DPBTMatureDeposit", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "DPBTMatureDeposit", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("DPBTMatureDeposit.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_MaturityInstruction resp = new MO_OUT_MaturityInstruction();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
