package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.GL.*;

/**
 * TCS BaNCS Business Transaction: CLBTSettleInstruction
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class CLBTSettleInstruction {

    private final CLDGSettlementGrabber dataGrabber;
    private final ClearingHouseGatewayService service;

    public CLBTSettleInstruction() {
        this.dataGrabber = new CLDGSettlementGrabber();
        this.service = new ClearingHouseGatewayService();
    }

    public CLBTSettleInstruction(CLDGSettlementGrabber dataGrabber, ClearingHouseGatewayService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: CLBTSettleInstructionExecute
     */
    public MO_OUT_SettlementInstruct CLBTSettleInstructionExecute(MO_INP_SettlementInstruct req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "CLBTSettleInstruction");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "CLBTSettleInstruction");
        }

        // Step 2: Data Grabber state query
        SettlementInstruction entity = this.dataGrabber.fetchSettlementInstructionById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: CL -> GL
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "CLBTSettleInstruction", req.getMessageCorrelationId(), "GL");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "CLBTSettleInstruction", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("CLBTSettleInstruction.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_SettlementInstruct resp = new MO_OUT_SettlementInstruct();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
