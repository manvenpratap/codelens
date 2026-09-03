package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.GL.*;

/**
 * TCS BaNCS Business Transaction: CLBTMarginTransfer
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class CLBTMarginTransfer {

    private final CLDGFailGrabber dataGrabber;
    private final SettlementInstructionService service;

    public CLBTMarginTransfer() {
        this.dataGrabber = new CLDGFailGrabber();
        this.service = new SettlementInstructionService();
    }

    public CLBTMarginTransfer(CLDGFailGrabber dataGrabber, SettlementInstructionService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: CLBTMarginTransferExecute
     */
    public MO_OUT_DepositoryTransfer CLBTMarginTransferExecute(MO_INP_DepositoryTransfer req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "CLBTMarginTransfer");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "CLBTMarginTransfer");
        }

        // Step 2: Data Grabber state query
        SettlementFailRecord entity = this.dataGrabber.fetchSettlementFailRecordById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: CL -> GL
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "CLBTMarginTransfer", req.getMessageCorrelationId(), "GL");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "CLBTMarginTransfer", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("CLBTMarginTransfer.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_DepositoryTransfer resp = new MO_OUT_DepositoryTransfer();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
