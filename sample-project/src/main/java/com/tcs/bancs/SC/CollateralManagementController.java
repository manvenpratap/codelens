package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: CollateralManagementController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class CollateralManagementController {

    private final CollateralRegistrationService service;

    public CollateralManagementController() {
        this.service = new CollateralRegistrationService();
    }

    public CollateralManagementController(CollateralRegistrationService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: SCBTRegisterCollateral
     */
    public MO_OUT_CollateralRegistration SCBTRegisterCollateral(MO_INP_CollateralRegistration request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CollateralManagementController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.SCBTRegisterCollateral(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: SCETGetCollateralDetails
     */
    public MO_OUT_CollateralRegistration SCETGetCollateralDetails(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CollateralManagementController", queryKey, "INQUIRY");
        return this.service.SCETGetCollateralDetails(queryKey);
    }

    /**
     * Generic execute request handler delegating to SCBTRegisterCollateral.
     */
    public MO_OUT_CollateralRegistration handleExecuteRequest(MO_INP_CollateralRegistration request) {
        return this.SCBTRegisterCollateral(request);
    }

    /**
     * Generic inquiry request handler delegating to SCETGetCollateralDetails.
     */
    public MO_OUT_CollateralRegistration handleInquiryRequest(String queryKey) {
        return this.SCETGetCollateralDetails(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
