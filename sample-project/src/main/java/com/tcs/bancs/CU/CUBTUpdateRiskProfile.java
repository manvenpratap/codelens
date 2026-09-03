package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;


/**
 * TCS BaNCS Business Transaction: CUBTUpdateRiskProfile
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class CUBTUpdateRiskProfile {

    private final CUDGRelationshipGrabber dataGrabber;
    private final CustomerRiskRatingService service;

    public CUBTUpdateRiskProfile() {
        this.dataGrabber = new CUDGRelationshipGrabber();
        this.service = new CustomerRiskRatingService();
    }

    public CUBTUpdateRiskProfile(CUDGRelationshipGrabber dataGrabber, CustomerRiskRatingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: CUBTUpdateRiskProfileExecute
     */
    public MO_OUT_RiskRatingUpdate CUBTUpdateRiskProfileExecute(MO_INP_RiskRatingUpdate req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "CUBTUpdateRiskProfile");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "CUBTUpdateRiskProfile");
        }

        // Step 2: Data Grabber state query
        PartyRelationship entity = this.dataGrabber.fetchPartyRelationshipById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }



        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "CUBTUpdateRiskProfile", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("CUBTUpdateRiskProfile.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_RiskRatingUpdate resp = new MO_OUT_RiskRatingUpdate();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
