package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.LN.*;

/**
 * TCS BaNCS Business Transaction: SCBTRevalueCollateral
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class SCBTRevalueCollateral {

    private final SCDGPledgeGrabber dataGrabber;
    private final ValuationEngineService service;

    public SCBTRevalueCollateral() {
        this.dataGrabber = new SCDGPledgeGrabber();
        this.service = new ValuationEngineService();
    }

    public SCBTRevalueCollateral(SCDGPledgeGrabber dataGrabber, ValuationEngineService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: SCBTRevalueCollateralExecute
     */
    public MO_OUT_CollateralRevaluation SCBTRevalueCollateralExecute(MO_INP_CollateralRevaluation req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "SCBTRevalueCollateral");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "SCBTRevalueCollateral");
        }

        // Step 2: Data Grabber state query
        CollateralPledge entity = this.dataGrabber.fetchCollateralPledgeById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: SC -> LN
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "SCBTRevalueCollateral", req.getMessageCorrelationId(), "LN");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "SCBTRevalueCollateral", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("SCBTRevalueCollateral.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_CollateralRevaluation resp = new MO_OUT_CollateralRevaluation();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
