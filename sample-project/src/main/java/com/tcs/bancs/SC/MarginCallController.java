package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: MarginCallController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class MarginCallController {

    private final CollateralRegistrationService service;

    public MarginCallController() {
        this.service = new CollateralRegistrationService();
    }

    public MarginCallController(CollateralRegistrationService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: SCBTRevalueCollateral
     */
    public MO_OUT_CollateralRevaluation SCBTRevalueCollateral(MO_INP_CollateralRevaluation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "MarginCallController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.SCBTRevalueCollateral(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: SCETCalculateLTV
     */
    public MO_OUT_CollateralRevaluation SCETCalculateLTV(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "MarginCallController", queryKey, "INQUIRY");
        return this.service.SCETCalculateLTV(queryKey);
    }

    /**
     * Generic execute request handler delegating to SCBTRevalueCollateral.
     */
    public MO_OUT_CollateralRevaluation handleExecuteRequest(MO_INP_CollateralRevaluation request) {
        return this.SCBTRevalueCollateral(request);
    }

    /**
     * Generic inquiry request handler delegating to SCETCalculateLTV.
     */
    public MO_OUT_CollateralRevaluation handleInquiryRequest(String queryKey) {
        return this.SCETCalculateLTV(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
