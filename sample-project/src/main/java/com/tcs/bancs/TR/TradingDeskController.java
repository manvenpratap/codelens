package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: TradingDeskController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class TradingDeskController {

    private final TRBTSubmitOrder businessTransaction;
    private final TRETGetOrderStatus elementaryTransaction;

    public TradingDeskController() {
        this.businessTransaction = new TRBTSubmitOrder();
        this.elementaryTransaction = new TRETGetOrderStatus();
    }

    public TradingDeskController(TRBTSubmitOrder bt, TRETGetOrderStatus et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_OrderSubmission handleExecuteRequest(MO_INP_OrderSubmission request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "TradingDeskController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.TRBTSubmitOrderExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_OrderSubmission handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "TradingDeskController", queryKey, "INQUIRY");
        return this.elementaryTransaction.TRETGetOrderStatusFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
