package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: SettlementGatewayController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class SettlementGatewayController {

    private final ClearingHouseGatewayService service;

    public SettlementGatewayController() {
        this.service = new ClearingHouseGatewayService();
    }

    public SettlementGatewayController(ClearingHouseGatewayService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: CLBTProcessNetting
     */
    public MO_OUT_NettingRequest CLBTProcessNetting(MO_INP_NettingRequest request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "SettlementGatewayController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.CLBTProcessNetting(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: CLETQueryNettingObligations
     */
    public MO_OUT_NettingRequest CLETQueryNettingObligations(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "SettlementGatewayController", queryKey, "INQUIRY");
        return this.service.CLETQueryNettingObligations(queryKey);
    }

    /**
     * Generic execute request handler delegating to CLBTProcessNetting.
     */
    public MO_OUT_NettingRequest handleExecuteRequest(MO_INP_NettingRequest request) {
        return this.CLBTProcessNetting(request);
    }

    /**
     * Generic inquiry request handler delegating to CLETQueryNettingObligations.
     */
    public MO_OUT_NettingRequest handleInquiryRequest(String queryKey) {
        return this.CLETQueryNettingObligations(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
