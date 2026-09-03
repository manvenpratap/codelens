package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: MarginCallController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class MarginCallController {

    private final SCBTRevalueCollateral businessTransaction;
    private final SCETCalculateLTV elementaryTransaction;

    public MarginCallController() {
        this.businessTransaction = new SCBTRevalueCollateral();
        this.elementaryTransaction = new SCETCalculateLTV();
    }

    public MarginCallController(SCBTRevalueCollateral bt, SCETCalculateLTV et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_CollateralRevaluation handleExecuteRequest(MO_INP_CollateralRevaluation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "MarginCallController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.SCBTRevalueCollateralExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_CollateralRevaluation handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "MarginCallController", queryKey, "INQUIRY");
        return this.elementaryTransaction.SCETCalculateLTVFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
