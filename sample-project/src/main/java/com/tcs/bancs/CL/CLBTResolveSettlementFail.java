package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.GL.*;

/**
 * TCS BaNCS Business Transaction: CLBTResolveSettlementFail
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class CLBTResolveSettlementFail {

    private final CLDGSettlementGrabber dataGrabber;
    private final FailManagementService service;

    public CLBTResolveSettlementFail() {
        this.dataGrabber = new CLDGSettlementGrabber();
        this.service = new FailManagementService();
    }

    public CLBTResolveSettlementFail(CLDGSettlementGrabber dataGrabber, FailManagementService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: CLBTResolveSettlementFailExecute
     */
    public MO_ClearingSummary CLBTResolveSettlementFailExecute(MO_SettlementObligation req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "CLBTResolveSettlementFail");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "CLBTResolveSettlementFail");
        }

        // Step 2: Data Grabber state query
        SettlementInstruction entity = this.dataGrabber.fetchSettlementInstructionById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: CL -> GL
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "CLBTResolveSettlementFail", req.getMessageCorrelationId(), "GL");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "CLBTResolveSettlementFail", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("CLBTResolveSettlementFail.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_ClearingSummary resp = new MO_ClearingSummary();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
