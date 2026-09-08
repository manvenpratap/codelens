package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: CustomerRelationshipController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class CustomerRelationshipController {

    private final CustomerOnboardingService service;

    public CustomerRelationshipController() {
        this.service = new CustomerOnboardingService();
    }

    public CustomerRelationshipController(CustomerOnboardingService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: CUBTUpdateRiskProfile
     */
    public MO_OUT_RiskRatingUpdate CUBTUpdateRiskProfile(MO_INP_RiskRatingUpdate request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CustomerRelationshipController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.CUBTUpdateRiskProfile(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: CUETFetchRelationships
     */
    public MO_OUT_RiskRatingUpdate CUETFetchRelationships(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CustomerRelationshipController", queryKey, "INQUIRY");
        return this.service.CUETFetchRelationships(queryKey);
    }

    /**
     * Generic execute request handler delegating to CUBTUpdateRiskProfile.
     */
    public MO_OUT_RiskRatingUpdate handleExecuteRequest(MO_INP_RiskRatingUpdate request) {
        return this.CUBTUpdateRiskProfile(request);
    }

    /**
     * Generic inquiry request handler delegating to CUETFetchRelationships.
     */
    public MO_OUT_RiskRatingUpdate handleInquiryRequest(String queryKey) {
        return this.CUETFetchRelationships(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
