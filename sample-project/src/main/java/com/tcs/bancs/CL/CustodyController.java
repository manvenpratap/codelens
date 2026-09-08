package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: CustodyController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class CustodyController {

    private final ClearingHouseGatewayService service;

    public CustodyController() {
        this.service = new ClearingHouseGatewayService();
    }

    public CustodyController(ClearingHouseGatewayService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: CLBTAffirmTrade
     */
    public MO_OUT_Affirmation CLBTAffirmTrade(MO_INP_Affirmation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CustodyController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.CLBTAffirmTrade(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: CLETGetDepositoryHoldings
     */
    public MO_OUT_Affirmation CLETGetDepositoryHoldings(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CustodyController", queryKey, "INQUIRY");
        return this.service.CLETGetDepositoryHoldings(queryKey);
    }

    /**
     * Generic execute request handler delegating to CLBTAffirmTrade.
     */
    public MO_OUT_Affirmation handleExecuteRequest(MO_INP_Affirmation request) {
        return this.CLBTAffirmTrade(request);
    }

    /**
     * Generic inquiry request handler delegating to CLETGetDepositoryHoldings.
     */
    public MO_OUT_Affirmation handleInquiryRequest(String queryKey) {
        return this.CLETGetDepositoryHoldings(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
