package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: ClearingController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class ClearingController {

    private final ClearingHouseGatewayService service;

    public ClearingController() {
        this.service = new ClearingHouseGatewayService();
    }

    public ClearingController(ClearingHouseGatewayService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: CLBTSettleInstruction
     */
    public MO_OUT_SettlementInstruct CLBTSettleInstruction(MO_INP_SettlementInstruct request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "ClearingController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.CLBTSettleInstruction(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: CLETCheckSettlementStatus
     */
    public MO_OUT_SettlementInstruct CLETCheckSettlementStatus(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "ClearingController", queryKey, "INQUIRY");
        return this.service.CLETCheckSettlementStatus(queryKey);
    }

    /**
     * Generic execute request handler delegating to CLBTSettleInstruction.
     */
    public MO_OUT_SettlementInstruct handleExecuteRequest(MO_INP_SettlementInstruct request) {
        return this.CLBTSettleInstruction(request);
    }

    /**
     * Generic inquiry request handler delegating to CLETCheckSettlementStatus.
     */
    public MO_OUT_SettlementInstruct handleInquiryRequest(String queryKey) {
        return this.CLETCheckSettlementStatus(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
