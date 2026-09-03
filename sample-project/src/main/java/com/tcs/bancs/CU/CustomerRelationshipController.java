package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: CustomerRelationshipController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class CustomerRelationshipController {

    private final CUBTUpdateRiskProfile businessTransaction;
    private final CUETFetchRelationships elementaryTransaction;

    public CustomerRelationshipController() {
        this.businessTransaction = new CUBTUpdateRiskProfile();
        this.elementaryTransaction = new CUETFetchRelationships();
    }

    public CustomerRelationshipController(CUBTUpdateRiskProfile bt, CUETFetchRelationships et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_RiskRatingUpdate handleExecuteRequest(MO_INP_RiskRatingUpdate request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CustomerRelationshipController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.CUBTUpdateRiskProfileExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_RiskRatingUpdate handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CustomerRelationshipController", queryKey, "INQUIRY");
        return this.elementaryTransaction.CUETFetchRelationshipsFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
