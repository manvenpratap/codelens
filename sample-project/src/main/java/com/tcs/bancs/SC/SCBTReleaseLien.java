package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.LN.*;

/**
 * TCS BaNCS Business Transaction: SCBTReleaseLien
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class SCBTReleaseLien {

    private final SCDGCollateralGrabber dataGrabber;
    private final LienManagementService service;

    public SCBTReleaseLien() {
        this.dataGrabber = new SCDGCollateralGrabber();
        this.service = new LienManagementService();
    }

    public SCBTReleaseLien(SCDGCollateralGrabber dataGrabber, LienManagementService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: SCBTReleaseLienExecute
     */
    public MO_LtvBreachAlert SCBTReleaseLienExecute(MO_CollateralValuationReport req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "SCBTReleaseLien");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "SCBTReleaseLien");
        }

        // Step 2: Data Grabber state query
        CollateralItem entity = this.dataGrabber.fetchCollateralItemById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: SC -> LN
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "SCBTReleaseLien", req.getMessageCorrelationId(), "LN");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "SCBTReleaseLien", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("SCBTReleaseLien.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_LtvBreachAlert resp = new MO_LtvBreachAlert();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
