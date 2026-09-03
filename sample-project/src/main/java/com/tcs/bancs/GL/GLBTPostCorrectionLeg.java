package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: GLBTPostCorrectionLeg
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class GLBTPostCorrectionLeg {

    private final GLDGLedgerGrabber dataGrabber;
    private final CostCenterAllocationService service;

    public GLBTPostCorrectionLeg() {
        this.dataGrabber = new GLDGLedgerGrabber();
        this.service = new CostCenterAllocationService();
    }

    public GLBTPostCorrectionLeg(GLDGLedgerGrabber dataGrabber, CostCenterAllocationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: GLBTPostCorrectionLegExecute
     */
    public MO_CostCenterRollup GLBTPostCorrectionLegExecute(MO_LedgerAuditReport req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "GLBTPostCorrectionLeg");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "GLBTPostCorrectionLeg");
        }

        // Step 2: Data Grabber state query
        LedgerAccount entity = this.dataGrabber.fetchLedgerAccountById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: GL -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "GLBTPostCorrectionLeg", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "GLBTPostCorrectionLeg", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("GLBTPostCorrectionLeg.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_CostCenterRollup resp = new MO_CostCenterRollup();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
