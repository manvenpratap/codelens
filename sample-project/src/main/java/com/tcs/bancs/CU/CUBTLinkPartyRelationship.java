package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;


/**
 * TCS BaNCS Business Transaction: CUBTLinkPartyRelationship
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class CUBTLinkPartyRelationship {

    private final CUDGExposureRollupGrabber dataGrabber;
    private final BeneficialOwnershipService service;

    public CUBTLinkPartyRelationship() {
        this.dataGrabber = new CUDGExposureRollupGrabber();
        this.service = new BeneficialOwnershipService();
    }

    public CUBTLinkPartyRelationship(CUDGExposureRollupGrabber dataGrabber, BeneficialOwnershipService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: CUBTLinkPartyRelationshipExecute
     */
    public MO_KycDocumentSummary CUBTLinkPartyRelationshipExecute(MO_CustomerRelationshipMap req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "CUBTLinkPartyRelationship");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "CUBTLinkPartyRelationship");
        }

        // Step 2: Data Grabber state query
        CustomerPepScreening entity = this.dataGrabber.fetchCustomerPepScreeningById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }



        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "CUBTLinkPartyRelationship", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("CUBTLinkPartyRelationship.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_KycDocumentSummary resp = new MO_KycDocumentSummary();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
