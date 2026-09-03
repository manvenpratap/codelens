package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: PortfolioAllocationController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class PortfolioAllocationController {

    private final TRBTExecuteTrade businessTransaction;
    private final TRETGetPositionSummary elementaryTransaction;

    public PortfolioAllocationController() {
        this.businessTransaction = new TRBTExecuteTrade();
        this.elementaryTransaction = new TRETGetPositionSummary();
    }

    public PortfolioAllocationController(TRBTExecuteTrade bt, TRETGetPositionSummary et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_TradeAllocation handleExecuteRequest(MO_INP_TradeAllocation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PortfolioAllocationController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.TRBTExecuteTradeExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_TradeAllocation handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PortfolioAllocationController", queryKey, "INQUIRY");
        return this.elementaryTransaction.TRETGetPositionSummaryFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
