package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.LN.*;

/**
 * TCS BaNCS Business Transaction: SCBTRegisterCollateral
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class SCBTRegisterCollateral {

    private final SCDGCollateralGrabber dataGrabber;
    private final CollateralRegistrationService service;

    public SCBTRegisterCollateral() {
        this.dataGrabber = new SCDGCollateralGrabber();
        this.service = new CollateralRegistrationService();
    }

    public SCBTRegisterCollateral(SCDGCollateralGrabber dataGrabber, CollateralRegistrationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: SCBTRegisterCollateralExecute
     */
    public MO_OUT_CollateralRegistration SCBTRegisterCollateralExecute(MO_INP_CollateralRegistration req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "SCBTRegisterCollateral");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "SCBTRegisterCollateral");
        }

        // Step 2: Data Grabber state query
        CollateralItem entity = this.dataGrabber.fetchCollateralItemById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: SC -> LN
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "SCBTRegisterCollateral", req.getMessageCorrelationId(), "LN");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "SCBTRegisterCollateral", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("SCBTRegisterCollateral.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_CollateralRegistration resp = new MO_OUT_CollateralRegistration();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
