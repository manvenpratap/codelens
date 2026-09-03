package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: CollateralManagementController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class CollateralManagementController {

    private final SCBTRegisterCollateral businessTransaction;
    private final SCETGetCollateralDetails elementaryTransaction;

    public CollateralManagementController() {
        this.businessTransaction = new SCBTRegisterCollateral();
        this.elementaryTransaction = new SCETGetCollateralDetails();
    }

    public CollateralManagementController(SCBTRegisterCollateral bt, SCETGetCollateralDetails et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_CollateralRegistration handleExecuteRequest(MO_INP_CollateralRegistration request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CollateralManagementController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.SCBTRegisterCollateralExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_CollateralRegistration handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CollateralManagementController", queryKey, "INQUIRY");
        return this.elementaryTransaction.SCETGetCollateralDetailsFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
