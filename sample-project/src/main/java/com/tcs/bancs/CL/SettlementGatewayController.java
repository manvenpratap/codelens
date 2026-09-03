package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: SettlementGatewayController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class SettlementGatewayController {

    private final CLBTProcessNetting businessTransaction;
    private final CLETQueryNettingObligations elementaryTransaction;

    public SettlementGatewayController() {
        this.businessTransaction = new CLBTProcessNetting();
        this.elementaryTransaction = new CLETQueryNettingObligations();
    }

    public SettlementGatewayController(CLBTProcessNetting bt, CLETQueryNettingObligations et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_NettingRequest handleExecuteRequest(MO_INP_NettingRequest request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "SettlementGatewayController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.CLBTProcessNettingExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_NettingRequest handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "SettlementGatewayController", queryKey, "INQUIRY");
        return this.elementaryTransaction.CLETQueryNettingObligationsFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
