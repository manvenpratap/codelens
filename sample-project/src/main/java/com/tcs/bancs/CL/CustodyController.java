package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: CustodyController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class CustodyController {

    private final CLBTAffirmTrade businessTransaction;
    private final CLETGetDepositoryHoldings elementaryTransaction;

    public CustodyController() {
        this.businessTransaction = new CLBTAffirmTrade();
        this.elementaryTransaction = new CLETGetDepositoryHoldings();
    }

    public CustodyController(CLBTAffirmTrade bt, CLETGetDepositoryHoldings et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_Affirmation handleExecuteRequest(MO_INP_Affirmation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CustodyController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.CLBTAffirmTradeExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_Affirmation handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CustodyController", queryKey, "INQUIRY");
        return this.elementaryTransaction.CLETGetDepositoryHoldingsFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
