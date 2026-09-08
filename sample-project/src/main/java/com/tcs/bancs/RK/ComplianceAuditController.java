package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: ComplianceAuditController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class ComplianceAuditController {

    private final MarketRiskService service;

    public ComplianceAuditController() {
        this.service = new MarketRiskService();
    }

    public ComplianceAuditController(MarketRiskService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: RKBTProcessAmlAlert
     */
    public MO_OUT_AmlScreening RKBTProcessAmlAlert(MO_INP_AmlScreening request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "ComplianceAuditController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.RKBTProcessAmlAlert(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: RKETCheckCounterpartyLimit
     */
    public MO_OUT_AmlScreening RKETCheckCounterpartyLimit(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "ComplianceAuditController", queryKey, "INQUIRY");
        return this.service.RKETCheckCounterpartyLimit(queryKey);
    }

    /**
     * Generic execute request handler delegating to RKBTProcessAmlAlert.
     */
    public MO_OUT_AmlScreening handleExecuteRequest(MO_INP_AmlScreening request) {
        return this.RKBTProcessAmlAlert(request);
    }

    /**
     * Generic inquiry request handler delegating to RKETCheckCounterpartyLimit.
     */
    public MO_OUT_AmlScreening handleInquiryRequest(String queryKey) {
        return this.RKETCheckCounterpartyLimit(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
