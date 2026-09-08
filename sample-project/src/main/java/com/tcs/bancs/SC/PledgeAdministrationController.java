package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: PledgeAdministrationController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class PledgeAdministrationController {

    private final CollateralRegistrationService service;

    public PledgeAdministrationController() {
        this.service = new CollateralRegistrationService();
    }

    public PledgeAdministrationController(CollateralRegistrationService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: SCBTCapitalizePledge
     */
    public MO_OUT_PledgeCreation SCBTCapitalizePledge(MO_INP_PledgeCreation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PledgeAdministrationController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.SCBTCapitalizePledge(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: SCETQueryActivePledges
     */
    public MO_OUT_PledgeCreation SCETQueryActivePledges(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PledgeAdministrationController", queryKey, "INQUIRY");
        return this.service.SCETQueryActivePledges(queryKey);
    }

    /**
     * Generic execute request handler delegating to SCBTCapitalizePledge.
     */
    public MO_OUT_PledgeCreation handleExecuteRequest(MO_INP_PledgeCreation request) {
        return this.SCBTCapitalizePledge(request);
    }

    /**
     * Generic inquiry request handler delegating to SCETQueryActivePledges.
     */
    public MO_OUT_PledgeCreation handleInquiryRequest(String queryKey) {
        return this.SCETQueryActivePledges(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
